package io.quantumdb.core.schema.definitions;

import static io.quantumdb.core.schema.definitions.Column.Hint.*;
import static io.quantumdb.core.schema.definitions.TestTypes.bigint;
import static io.quantumdb.core.schema.definitions.TestTypes.varchar;
import static org.junit.Assert.*;

import com.google.common.base.Strings;
import org.junit.Test;

public class ColumnTest {

	@Test
	public void testCreatingColumn() {
		Column column = new Column("id", bigint());

		assertEquals("id", column.getName());
		assertEquals(bigint(), column.getType());
		assertNull(column.getDefaultValue());
	}

	@Test
	public void testCreatingIdentityColumn() {
		Column column = new Column("id", bigint(), PRIMARY_KEY);

		assertTrue(column.isPrimaryKey());
	}

	@Test
	public void testCreatingAutoIncrementColumn() {
		Column column = new Column("id", bigint(), AUTO_INCREMENT);

		assertTrue(column.isAutoIncrement());
	}

	@Test
	public void testCreatingNonNullableColumn() {
		Column column = new Column("id", bigint(), NOT_NULL);

		assertTrue(column.isNotNull());
	}

	@Test
	public void testCreatingUniqueColumn() {
		Column column = new Column("id", bigint(), UNIQUE);

		assertTrue(column.isUnique());
	}

	@Test
	public void testCreatingColumnWithDefaultExpression() {
		Column column = new Column("id", varchar(255), "'unknown'");

		assertEquals("'unknown'", column.getDefaultValue());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCreatingColumnWithNullForColumnName() {
		new Column(null, bigint());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCreatingColumnWithEmptyStringForColumnName() {
		new Column("", bigint());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCreatingColumnWithNullForColumnType() {
		new Column("id", null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCreatingColumnWithNullHintThrowsException() {
		new Column("id", bigint(), null);
	}

	@Test
	public void testGetParentReturnsNullWhenColumnDoesNotBelongToTable() {
		Column column = new Column("id", bigint());

		assertNull(column.getParent());
	}

	@Test
	public void testGetParentReturnsParentTableWhenColumnBelongsToTable() {
		Table table = new Table("users");
		Column column = new Column("id", bigint());
		table.addColumn(column);

		assertEquals(table, column.getParent());
	}

	@Test
	public void testAddingForeignKeyToSingleColumn() {
		Table users = new Table("users")
				.addColumn(new Column("id", bigint(), PRIMARY_KEY, NOT_NULL, AUTO_INCREMENT))
				.addColumn(new Column("address_id", bigint(), NOT_NULL));

		Table addresses = new Table("addresses")
				.addColumn(new Column("id", bigint(), PRIMARY_KEY, NOT_NULL, AUTO_INCREMENT));

		ForeignKey constraint = users.addForeignKey("address_id")
				.referencing(addresses, "id");

		assertTrue(addresses.getForeignKeys().isEmpty());
		assertEquals(1, users.getForeignKeys().size());
		assertEquals(constraint, users.getForeignKeys().get(0));

		assertEquals(constraint, addresses.getColumn("id").getIncomingForeignKeys().get(0));
		assertEquals(constraint, users.getColumn("address_id").getOutgoingForeignKey());
	}

	@Test
	public void testAddingForeignKeyToMultiColumn() {
		Table items = new Table("items")
				.addColumn(new Column("id", bigint(), PRIMARY_KEY, NOT_NULL, AUTO_INCREMENT));

		Table locations = new Table("locations")
				.addColumn(new Column("id", bigint(), PRIMARY_KEY, NOT_NULL, AUTO_INCREMENT));

		Table stocks = new Table("stocks")
				.addColumn(new Column("item_id", bigint(), PRIMARY_KEY, NOT_NULL))
				.addColumn(new Column("location_id", bigint(), PRIMARY_KEY, NOT_NULL))
				.addColumn(new Column("quantity", bigint(), NOT_NULL));

		stocks.addForeignKey("item_id").referencing(items, "id");
		stocks.addForeignKey("location_id").referencing(locations, "id");

		Table stockNotes = new Table("stock_notes")
				.addColumn(new Column("item_id", bigint(), PRIMARY_KEY, NOT_NULL))
				.addColumn(new Column("location_id", bigint(), PRIMARY_KEY, NOT_NULL))
				.addColumn(new Column("notes", varchar(255), NOT_NULL));

		ForeignKey constraint = stockNotes.addForeignKey("item_id", "location_id")
				.referencing(stocks, "item_id", "location_id");

		assertEquals(1, stockNotes.getForeignKeys().size());
		assertEquals(constraint, stockNotes.getForeignKeys().get(0));

		assertEquals(constraint, stocks.getColumn("item_id").getIncomingForeignKeys().get(0));
		assertEquals(constraint, stocks.getColumn("location_id").getIncomingForeignKeys().get(0));

		assertEquals(constraint, stockNotes.getColumn("item_id").getOutgoingForeignKey());
		assertEquals(constraint, stockNotes.getColumn("location_id").getOutgoingForeignKey());
	}

	@Test
	public void testRemovingColumnWithOutgoingForeignKey() {
		Table users = new Table("users")
				.addColumn(new Column("id", bigint(), PRIMARY_KEY, NOT_NULL, AUTO_INCREMENT))
				.addColumn(new Column("address_id", bigint(), NOT_NULL));

		Table addresses = new Table("addresses")
				.addColumn(new Column("id", bigint(), PRIMARY_KEY, NOT_NULL, AUTO_INCREMENT));

		users.addForeignKey("address_id")
				.referencing(addresses, "id");

		users.removeColumn("address_id");

		assertTrue(addresses.getColumn("id").getIncomingForeignKeys().isEmpty());
		assertTrue(addresses.getForeignKeys().isEmpty());
		assertTrue(users.getForeignKeys().isEmpty());
	}

	@Test(expected = IllegalStateException.class)
	public void testRemovingColumnWithIncomingForeignKey() {
		Table users = new Table("users")
				.addColumn(new Column("id", bigint(), PRIMARY_KEY, NOT_NULL, AUTO_INCREMENT))
				.addColumn(new Column("address_id", bigint(), NOT_NULL));

		Table addresses = new Table("addresses")
				.addColumn(new Column("id", bigint(), PRIMARY_KEY, NOT_NULL, AUTO_INCREMENT))
				.addColumn(new Column("serial_id", bigint()));

		users.addForeignKey("address_id")
				.referencing(addresses, "serial_id");

		addresses.removeColumn("serial_id");

		assertTrue(addresses.getColumn("id").getIncomingForeignKeys().isEmpty());
		assertTrue(addresses.getForeignKeys().isEmpty());
		assertTrue(users.getForeignKeys().isEmpty());
	}

	@Test
	public void testRenamingColumn() {
		Column column = new Column("id", bigint());
		column.rename("uuid");

		assertEquals("uuid", column.getName());
	}

	@Test
	public void testRenamingColumnWhichBelongsToTable() {
		Table table = new Table("users");
		Column column = new Column("id", bigint());
		table.addColumn(column);
		column.rename("uuid");

		assertEquals("uuid", column.getName());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRenamingColumnWithNullForColumnName() {
		new Column("id", bigint()).rename(null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRenamingColumnWithEmptyStringForColumnName() {
		new Column("id", bigint()).rename("");
	}

	@Test
	public void testThatCopyMethodReturnsCopy() {
		Column column = new Column("id", bigint(), PRIMARY_KEY, AUTO_INCREMENT, NOT_NULL);
		Column copy = column.copy();

		assertEquals(column, copy);
		assertNotSame(column, copy);
	}

	@Test
	public void toStringReturnsSomething() {
		Column column = new Column("id", bigint(), "'0'", PRIMARY_KEY, NOT_NULL);

		assertFalse(Strings.isNullOrEmpty(column.toString()));
	}

}
