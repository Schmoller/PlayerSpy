package au.com.mineauz.PlayerSpy.search;

import org.bukkit.Material;

import au.com.mineauz.PlayerSpy.Pair;
import au.com.mineauz.PlayerSpy.Records.BlockChangeRecord;
import au.com.mineauz.PlayerSpy.Records.Record;

public class BlockConstraint extends Constraint 
{
	public boolean placed;
	public Pair<Material, Integer> material;
	
	@Override
	public String toString() 
	{
		return "{ placed: " + placed + ", material: " + material + "}"; 
	}

	@Override
	public boolean matches( Record record )
	{
		if(!(record instanceof BlockChangeRecord))
			return false;
		
		BlockChangeRecord change = (BlockChangeRecord)record;
		
		if(change.wasPlaced() != placed)
			return false;
		
		if(change.getBlock().getType().equals(material.getArg1()) && change.getBlock().getData() == material.getArg2())
			return true;
		
		return false;
	}
}
