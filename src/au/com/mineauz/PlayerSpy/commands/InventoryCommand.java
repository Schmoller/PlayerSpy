package au.com.mineauz.PlayerSpy.commands;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;

import au.com.mineauz.PlayerSpy.InventoryViewer;
import au.com.mineauz.PlayerSpy.SpyPlugin;
import au.com.mineauz.PlayerSpy.LogTasks.DisplayInventoryTask;
import au.com.mineauz.PlayerSpy.Utilities.Match;
import au.com.mineauz.PlayerSpy.Utilities.Util;
import au.com.mineauz.PlayerSpy.Utilities.Utility;

public class InventoryCommand implements ICommand
{

	@Override
	public String getName()
	{
		return "inventory";
	}

	@Override
	public String[] getAliases()
	{
		return new String[] {"inv"};
	}

	@Override
	public String getPermission()
	{
		return "playerspy.inventory";
	}

	@Override
	public String[] getUsageString( String label, CommandSender sender)
	{
		return new String[] {label + ChatColor.GOLD + " <player> " + ChatColor.GREEN + "[date]"};
	}
	
	@Override
	public String getDescription()
	{
		return "Displays the inventory for a player at the specified time or current.";
	}

	@Override
	public boolean canBeConsole()
	{
		return true;
	}

	@Override
	public boolean canBeCommandBlock()
	{
		return false;
	}

	
	public void showInventory(CommandSender toWho, Inventory inv, OfflinePlayer offlineOwner, boolean canEdit)
	{
		if(toWho instanceof Player)
			InventoryViewer.openInventory(inv, (Player)toWho, offlineOwner, canEdit );
		else
			InventoryViewer.printInventory(inv, toWho);
	}
	@Override
	public boolean onCommand( CommandSender sender, String label, String[] args )
	{
		if(args.length < 1)
			return false;
		
		String playerName = args[0];
		
		long current = System.currentTimeMillis(); 
		long date = current;
		
		if(args.length >= 2)
		{
			String dateStr = "";
			
			for(int i = 1; i < args.length; ++i)
			{
				if(!dateStr.isEmpty())
					dateStr += " ";
				dateStr += args[i];
			}
			
			Match m = Util.parseDate(dateStr, 0, 0, 0);
			if(m == null)
				date = 0;
			else
				date = (Long)m.value;
		}
		
		if(date == 0)
		{
			sender.sendMessage(ChatColor.RED + "Invalid date/time format");
			return true;
		}
		
		if(date >= current)
		{
			Player toView = Bukkit.getPlayer(playerName);
			if(toView != null)
			{
				showInventory(sender, toView.getInventory(), null, sender.hasPermission("playerspy.inventory.edit"));
			}
			else
			{
				OfflinePlayer oPlayer = Bukkit.getOfflinePlayer(playerName);
				
				if(oPlayer.hasPlayedBefore())
				{
					PlayerInventory inv = Utility.getOfflinePlayerInventory(oPlayer);
					if(inv != null)
						showInventory(sender, inv, oPlayer, sender.hasPermission("playerspy.inventory.edit"));
					else
					{
						sender.sendMessage(ChatColor.RED + playerName + "'s inventory is unavailable.");
						return true;
					}
				}
				else
				{
					sender.sendMessage(ChatColor.RED + "Unknown player: " + playerName);
					return true;
				}
			}
		}
		else
		{
			OfflinePlayer oPlayer;
			
			oPlayer = Bukkit.getPlayer(playerName);
			if(oPlayer == null)
				oPlayer = Bukkit.getOfflinePlayer(playerName);
			
			if(!oPlayer.hasPlayedBefore())
			{
				sender.sendMessage(ChatColor.RED + "Unknown player: " + playerName);
				return true;
			}
			
			SpyPlugin.getExecutor().submit(new DisplayInventoryTask(oPlayer, sender, date));
		}
		
		return true;
	}

	@Override
	public List<String> onTabComplete( CommandSender sender, String label, String[] args )
	{
		return null;
	}

}
