package au.com.mineauz.PlayerSpy.legacy.v2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import au.com.mineauz.PlayerSpy.Records.ILocationAware;
import au.com.mineauz.PlayerSpy.Records.IRollbackable;
import au.com.mineauz.PlayerSpy.Records.Record;
import au.com.mineauz.PlayerSpy.Records.RecordFormatException;
import au.com.mineauz.PlayerSpy.Records.RecordType;
import au.com.mineauz.PlayerSpy.Utilities.Utility;
import au.com.mineauz.PlayerSpy.legacy.StoredItemStack;
import au.com.mineauz.PlayerSpy.storage.StoredInventoryInformation;
import au.com.mineauz.PlayerSpy.storage.StoredInventoryInformation.InventoryType;

@Deprecated
public class InventoryTransactionRecord extends Record implements IRollbackable, ILocationAware
{
	private ItemStack mItem;
	private StoredInventoryInformation mInvInfo;
	private boolean mTake;
	private boolean mIsRolledBack;
	
	public static InventoryTransactionRecord newTakeFromInventory(ItemStack item, Inventory inventory, Location enderChestLocation)
	{
		InventoryTransactionRecord record = new InventoryTransactionRecord();
		record.mItem = item.clone();
		record.mInvInfo = new StoredInventoryInformation(inventory, enderChestLocation);
		record.mTake = true;
		return record;
	}
	public static InventoryTransactionRecord newAddToInventory(ItemStack item, Inventory inventory, Location enderChestLocation)
	{
		InventoryTransactionRecord record = new InventoryTransactionRecord();
		record.mItem = item.clone();
		record.mInvInfo = new StoredInventoryInformation(inventory, enderChestLocation);
		record.mTake = false;
		return record;
	}

	public InventoryTransactionRecord() 
	{
		super(RecordType.ItemTransaction);
		mIsRolledBack = false;
	}

	/**
	 * Gets the item involved
	 */
	public ItemStack getItem()
	{
		return mItem;
	}
	/**
	 * Gets information about the inventory
	 */
	public StoredInventoryInformation getInventoryInfo()
	{
		return mInvInfo;
	}
	
	/**
	 * Gets whether they are taking from the inventory
	 */
	public boolean isTaking()
	{
		return mTake;
	}
	@Override
	protected void writeContents(DataOutputStream stream, boolean absolute) throws IOException 
	{
		stream.writeBoolean(mTake);
		new StoredItemStack(mItem).writeItemStack(stream);
		mInvInfo.write(stream, absolute);
		stream.writeBoolean(mIsRolledBack);
	}

	@Override
	protected void readContents(DataInputStream stream, World currentWorld, boolean absolute) throws IOException, RecordFormatException
	{
		mTake = stream.readBoolean();
		mItem = StoredItemStack.readItemStack(stream).getItem();
		mInvInfo = new StoredInventoryInformation();
		mInvInfo.read(stream, currentWorld, absolute);
		mIsRolledBack = stream.readBoolean();
	}

	@Override
	protected int getContentSize(boolean absolute) 
	{
		return 2 + new StoredItemStack(mItem).getSize() + mInvInfo.getSize(absolute);
	}

	@Override
	public String toString() 
	{
		return "InventoryTransaction {Item: " + mItem.toString() + ", Dir: " + (mTake ? "Taking" : "Putting") + ", " + mInvInfo.toString();
	}
	@Override
	public String getDescription()
	{
		String itemName = Utility.formatItemName(mItem);
		int amount = mItem.getAmount();
		
		String result = "(" + (mTake ? ChatColor.RED + "-" : ChatColor.GREEN + "+" ) + amount + ChatColor.RESET + ") " + itemName + (mTake ? " from " : " to ");
		if(mInvInfo.getBlock() != null)
		{
			String blockName = Utility.formatItemName(new ItemStack(mInvInfo.getBlock().getType(),1, mInvInfo.getBlock().getData()));
			result += ChatColor.DARK_AQUA + blockName + ChatColor.RESET;
		}
		else if(mInvInfo.getEntity() != null)
		{
			String entityName = (mInvInfo.getEntity().getEntityType() == EntityType.PLAYER ? mInvInfo.getEntity().getPlayerName() : mInvInfo.getEntity().getEntityType().getName());
			result += ChatColor.DARK_AQUA + entityName + ChatColor.RESET;
		}
		else if(mInvInfo.getType() == InventoryType.Player)
		{
			result += ChatColor.DARK_AQUA + mInvInfo.getPlayerName() + ChatColor.RESET;
		}
		else if(mInvInfo.getType() == InventoryType.Enderchest)
		{
			result += ChatColor.DARK_AQUA + "Ender Chest" + ChatColor.RESET;
		}
		else
		{
			result += ChatColor.DARK_AQUA + "Unknown" + ChatColor.RESET;
		}
		
		result += " by %s";
		return result;
	}
	@Override
	public boolean canBeRolledBack()
	{
		return true;
	}
	@Override
	public boolean wasRolledBack()
	{
		return mIsRolledBack;
	}
	@Override
	public boolean restore()
	{
		return false;
	}
	@Override
	public boolean rollback( boolean preview, Player previewTarget )
	{
		return false;
	}
	@Override
	public Location getLocation()
	{
		if(mInvInfo.getBlock() != null)
			return mInvInfo.getBlock().getLocation();
		else if(mInvInfo.getEntity() != null)
			return mInvInfo.getEntity().getLocation();
		return null;
	}
}