package io.quantumdb.core.schema.operations;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.quantumdb.core.schema.definitions.Column.Hint;
import io.quantumdb.core.schema.definitions.ColumnType;
import io.quantumdb.core.schema.definitions.Unique;
import lombok.Data;
import lombok.experimental.Accessors;


/**
 * This SchemaOperation describes an operation which creates a new table.
 */
@Data
@Accessors(chain = true)
public class CreateTable implements SchemaOperation {

	private final String tableName;
	private final List<ColumnDefinition> columns;
	private final List<Unique> uniques;

	CreateTable(String tableName) {
		checkArgument(!Strings.isNullOrEmpty(tableName), "You must specify a 'tableName'.");
		this.tableName = tableName;
		this.columns = Lists.newArrayList();
		this.uniques = Lists.newArrayList();
	}

	public CreateTable with(String name, ColumnType type, Hint... hints) {
		return with(name, type, null, hints);
	}

	public CreateTable with(String name, ColumnType type, String defaultValueExpression, Hint... hints) {
		checkArgument(!Strings.isNullOrEmpty(name), "You must specify a 'name'.");
		checkArgument(type != null, "You must specify a 'type'.");

		columns.add(new ColumnDefinition(name, type, defaultValueExpression, hints));
		return this;
	}

	public CreateTable withUnique(Unique unique) {
		checkArgument(unique != null, "The unique is null.");
		checkArgument(!this.uniques.contains(unique), "This table already contains that unique object.");
		this.uniques.add(unique);
		return this;
	}

	public ImmutableList<ColumnDefinition> getColumns() {
		return ImmutableList.copyOf(columns);
	}

}
