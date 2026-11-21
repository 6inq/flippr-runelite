package com.flippr.osrs;

// One non-coin stack in the inventory at snapshot time.
public class InventoryItemEntry
{
	int itemId;
	String itemName;
	int quantity;

	InventoryItemEntry(int itemId, String itemName, int quantity)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.quantity = quantity;
	}
}
