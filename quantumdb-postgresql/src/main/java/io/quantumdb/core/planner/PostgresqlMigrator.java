package io.quantumdb.core.planner;

import static com.google.common.base.Preconditions.checkArgument;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.google.common.collect.*;
import io.quantumdb.core.backends.DatabaseMigrator;
import io.quantumdb.core.backends.planner.Operation;
import io.quantumdb.core.backends.planner.Plan;
import io.quantumdb.core.backends.planner.PlanValidator;
import io.quantumdb.core.backends.planner.Step;
import io.quantumdb.core.backends.postgresql.migrator.ViewCreator;
import io.quantumdb.core.migration.Migrator.Stage;
import io.quantumdb.core.migration.VersionTraverser.Direction;
import io.quantumdb.core.schema.definitions.Catalog;
import io.quantumdb.core.schema.definitions.Column;
import io.quantumdb.core.schema.definitions.Sequence;
import io.quantumdb.core.schema.definitions.Table;
import io.quantumdb.core.schema.operations.DataOperation;
import io.quantumdb.core.schema.operations.Operation.Type;
import io.quantumdb.core.utils.QueryBuilder;
import io.quantumdb.core.versioning.ChangeSet;
import io.quantumdb.core.versioning.RefLog;
import io.quantumdb.core.versioning.RefLog.ColumnRef;
import io.quantumdb.core.versioning.RefLog.SyncRef;
import io.quantumdb.core.versioning.RefLog.TableRef;
import io.quantumdb.core.versioning.State;
import io.quantumdb.core.versioning.Version;
import io.quantumdb.query.rewriter.PostgresqlQueryRewriter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class PostgresqlMigrator implements DatabaseMigrator {

	private static void execute(Connection connection, QueryBuilder queryBuilder) throws SQLException {
		String query = queryBuilder.toString();
		try (Statement statement = connection.createStatement()) {
			log.debug("Executing: " + query);
			statement.execute(query);
		}
	}

	private final PostgresqlBackend backend;

	PostgresqlMigrator(PostgresqlBackend backend) {
		this.backend = backend;
	}

	@Override
	public void applySchemaChanges(State state, Version from, Version to) throws MigrationException {
		RefLog refLog = state.getRefLog();
		Set<Version> preMigration = refLog.getVersions();
		Plan plan = new PostgresqlMigrationPlanner().createPlan(state, from, to);

		PlanValidator.validate(plan);
		Set<Version> postMigration = refLog.getVersions();
		Set<Version> intermediateVersions = Sets.newHashSet(Sets.difference(postMigration, preMigration));
		intermediateVersions.remove(to);

		new InternalPlanner(backend, plan, state, from, to, intermediateVersions).migrate();
	}

	@Override
	public void applyDataChanges(State state, Stage stage) throws MigrationException {
		List<Version> versions = stage.getVersions();
		Map<Version, DataOperation> operations = Maps.newLinkedHashMap();
		for (Version version : versions) {
			io.quantumdb.core.schema.operations.Operation operation = version.getOperation();
			if (!(operation instanceof DataOperation)) {
				throw new IllegalArgumentException("This stage contains non-data steps!");
			}

			DataOperation dataOperation = (DataOperation) operation;
			operations.put(version, dataOperation);
		}

		RefLog refLog = state.getRefLog();

		PostgresqlQueryRewriter rewriter = new PostgresqlQueryRewriter();
		Map<String, String> mapping = refLog.getTableRefs(stage.getParent()).stream()
				.collect(Collectors.toMap(TableRef::getName, TableRef::getRefId));
		rewriter.setTableMapping(mapping);

		try (Connection connection = backend.connect()) {
			connection.setAutoCommit(false);
			for (Entry<Version, DataOperation> entry : operations.entrySet()) {
				try (Statement statement = connection.createStatement()) {
					DataOperation dataOperation = entry.getValue();
					String query = dataOperation.getQuery();
					String rewrittenQuery = rewriter.rewrite(query);
					statement.executeUpdate(rewrittenQuery);
					refLog.fork(entry.getKey());
				}
				catch (SQLException e) {
					connection.rollback();
					throw e;
				}
			}
			connection.commit();
			backend.persistState(state);
		}
		catch (SQLException e) {
			throw new MigrationException("Exception happened while performing data changes.", e);
		}
	}

	@Override
	public void drop(State state, Version version) throws MigrationException {
		// Check that the version's operation is of type DDL, or has no operation (root version).
		checkArgument(version.getOperation() == null || version.getOperation().getType() == Type.DDL);

		RefLog refLog = state.getRefLog();
		Catalog catalog = state.getCatalog();

		Set<Version> activeVersions = refLog.getVersions();
		checkArgument(activeVersions.contains(version), "Version to drop is not part of the active versions!");
		Set<Version> newActiveVersions = activeVersions.stream()
				.filter(version1 -> !version1.equals(version))
				.collect(Collectors.toSet());

		// Check if version is at the end of the active versions
		Version first = version;
		Version last = version;
		Version intermediateV = version.getChild();
		while (intermediateV != null) {
			if (activeVersions.contains(intermediateV)) {
				last = intermediateV;
			}
			intermediateV = intermediateV.getChild();
		}
		// Or first
		intermediateV = version.getParent();
		while (intermediateV != null) {
			if (activeVersions.contains(intermediateV)) {
				first = intermediateV;
			}
			intermediateV = intermediateV.getParent();
		}

		checkArgument(!(first.equals(version) && last.equals(version)), "Cannot drop the only active version!");
		checkArgument(first.equals(version) || last.equals(version), "The version to drop is not at the beginning or end of the active versions!");

		List<Version> versionsToDrop = new ArrayList<Version>();
		List<Version> versionsToKeep = new ArrayList<Version>();

		//New implementation - active version to drop should always be the previous versions to drop
		ChangeSet changeSetToDrop = version.getChangeSet();
		versionsToDrop.add(version);
		Version intermediateVersion = version.getParent();
		while (true) {
			if (intermediateVersion != null && intermediateVersion.getChangeSet().equals(changeSetToDrop)) {
				versionsToDrop.add(intermediateVersion);
				intermediateVersion = intermediateVersion.getParent();
			} else {
				break;
			}
		}

		LinkedHashSet<TableRef> tablesToDropSet = new LinkedHashSet<TableRef>();
		for (Version version1 : versionsToDrop) {
			tablesToDropSet.addAll(refLog.getTableRefs().stream()
					.filter(tableRef -> tableRef.getVersions().contains(version1))
					.filter(tableRef -> tableRef.getVersions().stream().noneMatch(newActiveVersions::contains))
					// only drop table if it actually exists in the catalog because it may already be deleted
					.filter(tableRef -> catalog.getTables().stream().anyMatch(table -> tableRef.getRefId().equals(table.getName())))
					.collect(Collectors.toSet()));
		}

		Map<SyncRef, SyncFunction> newSyncFunctions = Maps.newLinkedHashMap();

		List<TableRef> tablesToDrop = new ArrayList<>(tablesToDropSet);

		log.info("Determined the following tables will be dropped: {}", tablesToDrop);
		for (TableRef tableRef : tablesToDrop) {
			Set<SyncRef> inbounds = tableRef.getInboundSyncs();
			Set<SyncRef> outbounds = tableRef.getOutboundSyncs();

			for (SyncRef inbound : inbounds) {
				TableRef source = inbound.getSource();
				Direction direction = inbound.getDirection();

				Map<ColumnRef, ColumnRef> inboundMapping = inbound.getColumnMapping();
				for (SyncRef outbound : outbounds) {
					if (outbound.getDirection() != direction) {
						continue;
					}

					TableRef target = outbound.getTarget();
					Map<ColumnRef, ColumnRef> outboundMapping = outbound.getColumnMapping();

					// TODO: Somehow persist default values ?
					Map<ColumnRef, ColumnRef> newMapping = inboundMapping.entrySet().stream()
							.filter(entry -> {
								ColumnRef intermediate = entry.getValue();
								return outboundMapping.containsKey(intermediate);
							})
							.collect(Collectors.toMap(Entry::getKey, entry -> outboundMapping.get(entry.getValue())));

					Set<String> columnsToMigrate = newMapping.values().stream()
							.map(ColumnRef::getName)
							.collect(Collectors.toSet());

					SyncFunction sync = new SyncFunction(refLog, source, target, newMapping, catalog, new NullRecords());
					sync.setColumnsToMigrate(columnsToMigrate);

					SyncRef syncRef = refLog.addSync(sync.getTriggerName(), sync.getFunctionName(), newMapping);
					newSyncFunctions.put(syncRef, sync);
				}
			}
		}

		try (Connection connection = backend.connect()) {
			connection.setAutoCommit(false);

			dropSynchronizers(connection, state.getRefLog(), tablesToDrop);
			for (SyncFunction syncFunction : newSyncFunctions.values()) {
				execute(connection, syncFunction.createFunctionStatement());
				execute(connection, syncFunction.createTriggerStatement());
			}
			dropTables(connection, refLog, catalog, tablesToDrop);
			refLog.setVersionState(version, false);
			backend.persistState(state);
			connection.commit();
		}
		catch (SQLException e) {
			throw new MigrationException(e);
		}
	}

	private void dropSynchronizers(Connection connection, RefLog refLog, List<TableRef> tablesToDrop)
			throws SQLException {

		connection.setAutoCommit(false);

		for (TableRef table : tablesToDrop) {
			String refId = table.getRefId();
			TableRef tableRef = refLog.getTableRefById(refId);
			Set<SyncRef> tableSyncs = Sets.newHashSet();
			tableSyncs.addAll(tableRef.getInboundSyncs());
			tableSyncs.addAll(tableRef.getOutboundSyncs());

			for (SyncRef tableSync : tableSyncs) {
				dropSynchronizer(connection, tableSync);
			}
		}

		connection.commit();
	}

	private void dropSynchronizer(Connection connection, SyncRef sync) throws SQLException {
		String triggerName = sync.getName();
		String functionName = sync.getFunctionName();
		String sourceRefId = sync.getSource().getRefId();
		String targetRefId = sync.getTarget().getRefId();

		try (Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER " + triggerName + " ON " + sourceRefId + ";");
			statement.execute("DROP FUNCTION " + functionName + "();");
			sync.drop();
			log.info("Dropped synchronizer: {}/{} for: {} -> {}", triggerName, functionName, sourceRefId, targetRefId);
		}
	}

	private void dropTables(Connection connection, RefLog refLog, Catalog catalog, List<TableRef> tablesToDrop)
			throws SQLException {

		connection.setAutoCommit(false);

		Set<String> refIdsToDrop = tablesToDrop.stream()
				.map(TableRef::getRefId)
				.collect(Collectors.toSet());

		for (TableRef tableRef : tablesToDrop) {
			String refId = tableRef.getRefId();
			Table table = catalog.getTable(refId);

			Set<Sequence> usedSequences = table.getColumns().stream()
					.filter(column -> column.getSequence() != null)
					.map(Column::getSequence)
					.collect(Collectors.toSet());

			Set<Table> tablesNotToBeDeleted = catalog.getTables().stream()
					.filter(otherTable -> !refIdsToDrop.contains(otherTable.getName()))
					.collect(Collectors.toSet());

			for (Table otherTable : tablesNotToBeDeleted) {
				boolean reassigned = false;
				for (Column column : otherTable.getColumns()) {
					Sequence sequence = column.getSequence();
					if (sequence != null && usedSequences.contains(sequence)) {
						try (Statement statement = connection.createStatement()) {
							String sequenceName = sequence.getName();
							String target = otherTable.getName() + "." + column.getName();
							log.info("Reassigning sequence: {} to: {}", sequenceName, target);
							statement.execute("ALTER SEQUENCE " + sequenceName + " OWNED BY " + target + ";");
							usedSequences.remove(sequence);
							reassigned = true;
							break;
						}
					}
				}

				if (reassigned) {
					break;
				}
			}

			try (Statement statement = connection.createStatement()) {
				statement.execute("DROP TABLE " + refId + " CASCADE;");
			}
		}

		connection.commit();
		tablesToDrop.forEach(refLog::dropTable);
	}

	static class InternalPlanner {

		private final Plan plan;
		private final Set<Version> intermediateVersions;
		private final RefLog refLog;
		private final State state;
		private final NullRecords nullRecords;
		private final Multimap<Table, String> migratedColumns;
		private final PostgresqlBackend backend;
		private final Version from;
		private final Version to;

		private final com.google.common.collect.Table<String, String, SyncFunction> syncFunctions;


		public InternalPlanner(PostgresqlBackend backend, Plan plan, State state, Version from, Version to,
				Set<Version> intermediateVersions) {

			this.backend = backend;
			this.plan = plan;
			this.intermediateVersions = intermediateVersions;
			this.refLog = plan.getRefLog();
			this.state = state;
			this.nullRecords = new NullRecords();
			this.migratedColumns = HashMultimap.create();
			this.syncFunctions = HashBasedTable.create();
			this.from = from;
			this.to = to;
		}

		public void migrate() throws MigrationException {
			createGhostTables();

			Optional<Step> nextStep;
			while ((nextStep = plan.nextStep()).isPresent()) {
				try {
					Step step = nextStep.get();
					execute(step.getOperation());
					step.markAsExecuted();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new MigrationException(e);
				}
			}

			createIndexes();

			synchronizeBackwards();

			refLog.setVersionState(to, true);

			createViews(to);

			persistState();
		}

		private void persistState() throws MigrationException {
			try {
				backend.persistState(state);
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void execute(Operation operation) throws MigrationException, InterruptedException {

			log.info("Executing operation: " + operation);
			try {
				Set<Table> tables = operation.getTables();
				switch (operation.getType()) {
					case ADD_NULL:
						nullRecords.insertNullObjects(backend, tables);
						break;
					case DROP_NULL:
						nullRecords.deleteNullObjects(backend, tables);
						break;
					case COPY:
						Table table = tables.iterator().next();
						Set<String> columns = operation.getColumns();
						Set<String> previouslyMigrated = Sets.newHashSet(this.migratedColumns.get(table));
						Set<String> combined = Sets.union(previouslyMigrated, columns);

						synchronizeForwards(table, Sets.newHashSet(combined));
						copyData(table, previouslyMigrated, columns);
						this.migratedColumns.putAll(table, columns);
						break;
				}
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void createViews(Version version) throws MigrationException {
			try (Connection connection = backend.connect()) {
				ViewCreator creator = new ViewCreator();
				creator.create(connection, plan.getViews(), refLog, version);
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void createGhostTables() throws MigrationException {
			try (Connection connection = backend.connect()) {
				TableCreator creator = new TableCreator();
				creator.create(connection, plan.getGhostTables());
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void createIndexes() throws MigrationException {
			try (Connection connection = backend.connect()) {
				TableCreator creator = new TableCreator();
				creator.createIndexes(connection, plan.getGhostTables());
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void synchronizeForwards(Table targetTable, Set<String> targetColumns) throws SQLException {
			log.info("Creating forward sync function for table: {}...", targetTable.getName());
			try (Connection connection = backend.connect()) {
				Catalog catalog = state.getCatalog();
				Multimap<TableRef, TableRef> tableMapping = state.getRefLog().getTableMapping(from, to);
				for (Entry<TableRef, TableRef> entry : tableMapping.entries()) {
					if (entry.getValue().getRefId().equals(targetTable.getName())) {
						TableRef source = entry.getKey();
						TableRef target = entry.getValue();
						ensureSyncFunctionExists(connection, refLog, source, target, catalog, targetColumns);
					}
				}
			}
		}

		private void copyData(Table targetTable, Set<String> migratedColumns, Set<String> columnsToMigrate)
				throws SQLException, InterruptedException {

			Catalog catalog = state.getCatalog();
			Multimap<TableRef, TableRef> tableMapping = state.getRefLog().getTableMapping(from, to);
			for (Entry<TableRef, TableRef> entry : tableMapping.entries()) {
				if (entry.getValue().getRefId().equals(targetTable.getName())) {
					Table source = catalog.getTable(entry.getKey().getRefId());
					Table target = catalog.getTable(entry.getValue().getRefId());
					TableDataMigrator tableDataMigrator = new TableDataMigrator(backend, refLog);
					tableDataMigrator.migrateData(nullRecords, source, target, from, to, migratedColumns, columnsToMigrate);
				}
			}
		}

		private void synchronizeBackwards() throws MigrationException {
			log.info("Creating backwards sync functions...");
			try (Connection connection = backend.connect()) {
				Catalog catalog = state.getCatalog();
				Multimap<TableRef, TableRef> tableMapping = state.getRefLog().getTableMapping(from, to);
				for (Entry<TableRef, TableRef> entry : tableMapping.entries()) {
					TableRef target = entry.getKey();
					TableRef source = entry.getValue();
					Table targetTable = catalog.getTable(target.getRefId());

					Set<String> columns = targetTable.getColumns().stream()
							.map(Column::getName)
							.collect(Collectors.toSet());

					log.info("Creating backward sync function for table: {}...", target.getName());
					ensureSyncFunctionExists(connection, refLog, source, target, catalog, columns);
				}
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		void ensureSyncFunctionExists(Connection connection, RefLog refLog, TableRef source,
				TableRef target, Catalog catalog, Set<String> columns) throws SQLException {

			String sourceRefId = source.getRefId();
			String targetRefId = target.getRefId();

			SyncFunction syncFunction = syncFunctions.get(sourceRefId, targetRefId);
			if (syncFunction == null) {
				Map<ColumnRef, ColumnRef> mapping = refLog.getColumnMapping(source, target);
				syncFunction = new SyncFunction(refLog, source, target, mapping, catalog, nullRecords);
				syncFunction.setColumnsToMigrate(columns);
				syncFunctions.put(sourceRefId, targetRefId, syncFunction);

				log.info("Creating sync function: {} for table: {}", syncFunction.getFunctionName(), sourceRefId);
				PostgresqlMigrator.execute(connection, syncFunction.createFunctionStatement());

				log.info("Creating trigger: {} for table: {}", syncFunction.getTriggerName(), sourceRefId);
				PostgresqlMigrator.execute(connection, syncFunction.createTriggerStatement());

				Map<ColumnRef, ColumnRef> columnMapping = refLog.getColumnMapping(source, target);
				refLog.addSync(syncFunction.getTriggerName(), syncFunction.getFunctionName(), columnMapping);
			}
			else {
				syncFunction.setColumnsToMigrate(columns);

				log.info("Updating sync function: {} for table: {}", syncFunction.getFunctionName(), sourceRefId);
				PostgresqlMigrator.execute(connection, syncFunction.createFunctionStatement());

				TableRef sourceTable = refLog.getTableRefById(sourceRefId);
				sourceTable.getOutboundSyncs().stream()
						.filter(ref -> ref.getTarget().equals(target))
						.forEach(ref -> refLog.getColumnMapping(source, target).forEach((from, to) -> {
							boolean exists = ref.getColumnMapping().entrySet().stream()
									.anyMatch(entry -> entry.getKey().equals(from) && entry.getValue().equals(to));
							if (!exists) {
								ref.addColumnMapping(from, to);
							}
						}));

				TableRef targetTable = refLog.getTableRefById(targetRefId);
				targetTable.getInboundSyncs().stream()
						.filter(ref -> ref.getSource().equals(source))
						.forEach(ref -> refLog.getColumnMapping(target, source).forEach((from, to) -> {
							boolean exists = ref.getColumnMapping().entrySet().stream()
									.anyMatch(entry -> entry.getKey().equals(from) && entry.getValue().equals(to));
							if (!exists) {
								ref.addColumnMapping(from, to);
							}
						}));
			}
		}
	}
}
