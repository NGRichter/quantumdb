package io.quantumdb.core.schema.definitions;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Catalog implements Copyable<Catalog> {

	private final String name;
	private final Collection<Table> tables;
	private final Collection<View> views;
	private final Collection<Sequence> sequences;

	public Catalog(String name) {
		checkArgument(!Strings.isNullOrEmpty(name), "You must specify a 'name'");

		this.name = name;
		this.tables = Sets.newTreeSet(Comparator.comparing(Table::getName));
		this.views = Sets.newTreeSet(Comparator.comparing(View::getName));
		this.sequences = Sets.newTreeSet(Comparator.comparing(Sequence::getName));
	}

	public Catalog addTable(Table table) {
		checkArgument(table != null, "You must specify a 'table'.");
		checkArgument(!containsTable(table.getName()), "Catalog: '" + name + "' already contains a table: '" + table.getName() + "'.");
		checkArgument(!containsView(table.getName()), "Catalog: '" + name + "' already contains a view: '" + table.getName() + "'.");
		checkArgument(!table.getColumns().isEmpty(), "Table: '" + table.getName() + "' doesn't contain any columns.");
		// ToDo look into migration without primary keys
		checkArgument(!table.getPrimaryKeyColumns().isEmpty(), "Table: '" + table.getName() + "' has no primary key columns.");

		tables.add(table);
		table.setParent(this);
		return this;
	}

	public boolean containsTable(String tableName) {
		checkArgument(!Strings.isNullOrEmpty(tableName), "You must specify a 'tableName'");

		return tables.stream()
				.anyMatch(t -> t.getName().equals(tableName));
	}

	public Table getTable(String tableName) {
		checkArgument(!Strings.isNullOrEmpty(tableName), "You must specify a 'tableName'");

		return tables.stream()
				.filter(t -> t.getName().equals(tableName))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"Catalog: " + name + " does not contain a table: " + tableName));
	}

	public Table removeTable(String tableName) {
		Table table = getTable(tableName);
		table.canBeDropped();

		tables.remove(table);
		table.setParent(null);
		table.dropOutgoingForeignKeys();

		return table;
	}

	public Catalog addView(View view) {
		checkArgument(view != null, "You must specify a 'view'.");
		checkArgument(!containsTable(view.getName()), "Catalog: '" + name + "' already contains a table: '" + view.getName() + "'.");
		checkArgument(!containsView(view.getName()), "Catalog: '" + name + "' already contains a view: '" + view.getName() + "'.");

		view.setParent(this);
		views.add(view);
		return this;
	}

	public boolean containsView(String viewName) {
		checkArgument(!Strings.isNullOrEmpty(viewName), "You must specify a 'viewName'");

		return views.stream()
				.anyMatch(v -> v.getName().equals(viewName));
	}

	public View getView(String viewName) {
		checkArgument(!Strings.isNullOrEmpty(viewName), "You must specify a 'viewName'");

		return views.stream()
				.filter(v -> v.getName().equals(viewName))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"Catalog: " + name + " does not contain a view: " + viewName));
	}

	public View removeView(String viewName) {
		View view = getView(viewName);

		tables.remove(view);
		view.setParent(null);

		return view;
	}

	public Catalog addSequence(Sequence sequence) {
		checkArgument(sequence != null, "You must specify a 'sequence'");

		sequences.add(sequence);
		sequence.setParent(this);
		return this;
	}

	public Sequence removeSequence(String sequenceName) {
		checkArgument(!Strings.isNullOrEmpty(sequenceName), "You must specify a 'sequenceName'");

		Sequence sequence = getSequence(sequenceName);
		sequences.remove(sequence);
		sequence.setParent(null);

		tables.stream()
				.flatMap(table -> table.getColumns().stream())
				.filter(column -> sequence.equals(column.getSequence()))
				.forEach(Column::dropDefaultValue);

		return sequence;
	}

	private Sequence getSequence(String sequenceName) {
		checkArgument(!Strings.isNullOrEmpty(sequenceName), "You must specify a 'sequenceName'");

		return sequences.stream()
				.filter(sequence -> sequence.getName().equals(sequenceName))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"Catalog: " + name + " does not contain a sequence: " + sequenceName));
	}

	public ImmutableSet<Table> getTables() {
		return ImmutableSet.copyOf(tables);
	}

	public ImmutableSet<View> getViews() {
		return ImmutableSet.copyOf(views);
	}

	public ImmutableSet<ForeignKey> getForeignKeys() {
		return ImmutableSet.copyOf(tables.stream()
				.flatMap(table -> table.getForeignKeys().stream())
				.collect(Collectors.toSet()));
	}

	public ImmutableSet<Index> getIndexes() {
		return ImmutableSet.copyOf(tables.stream()
				.flatMap(table -> table.getIndexes().stream())
				.collect(Collectors.toSet()));
	}

	@Override
	public Catalog copy() {
		Catalog schema = new Catalog(name);
		for (Table table : tables) {
			schema.addTable(table.copy());
		}
		for (View view : views) {
			schema.addView(view.copy());
		}
		for (ForeignKey foreignKey : getForeignKeys()) {
			Table source = schema.getTable(foreignKey.getReferencingTableName());
			Table target = schema.getTable(foreignKey.getReferredTableName());
			source.addForeignKey(foreignKey.getReferencingColumns())
					.named(foreignKey.getForeignKeyName())
					.onDelete(foreignKey.getOnDelete())
					.onUpdate(foreignKey.getOnUpdate())
					.referencing(target, foreignKey.getReferredColumns());
		}
		for (Sequence sequence : sequences) {
			schema.addSequence(sequence.copy());
		}
		return schema;
	}

	@Override
	public String toString() {
		return PrettyPrinter.prettyPrint(this);
	}

	public Set<String> getTablesReferencingTable(String tableName) {
		return tables.stream()
				.filter(table -> table.referencesTable(tableName))
				.map(Table::getName)
				.collect(Collectors.toSet());
	}

}
