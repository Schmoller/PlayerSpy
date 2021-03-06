package au.com.mineauz.PlayerSpy.Records;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.bukkit.Location;
import org.bukkit.World;

import au.com.mineauz.PlayerSpy.storage.StoredLocation;

public class SleepRecord extends Record implements IPlayerLocationAware 
{

	public SleepRecord(boolean isSleeping, Location bedLocation) 
	{
		super(RecordType.Sleep);
		mLocation = new StoredLocation(bedLocation);
	}
	public SleepRecord()
	{
		super(RecordType.Sleep);
	}

	@Override
	protected void writeContents(DataOutputStream stream, boolean absolute) throws IOException 
	{
		stream.writeBoolean(mIsSleeping);
		mLocation.writeLocation(stream, absolute);
	}
	@Override
	protected void readContents(DataInputStream stream, World currentWorld, boolean absolute) throws IOException, RecordFormatException
	{
		mIsSleeping = stream.readBoolean();
		if(absolute)
			mLocation = StoredLocation.readLocationFull(stream);
		else
			mLocation = StoredLocation.readLocation(stream, currentWorld);
	}

	@Override
	protected int getContentSize(boolean absolute) 
	{
		return 1 + mLocation.getSize(absolute);
	}

	public boolean isSleeping()
	{
		return mIsSleeping;
	}
	
	public Location getBedLocation()
	{
		return mLocation.getLocation();
	}
	
	private boolean mIsSleeping;
	private StoredLocation mLocation;
	@Override
	public String getDescription()
	{
		if(mIsSleeping)
			return "%s went to sleep";
		else
			return "%s woke up";
	}
	@Override
	public Location getLocation()
	{
		return mLocation.getLocation();
	}
	@Override
	public boolean isFullLocation()
	{
		return false;
	}
	
	@Override
	public boolean equals( Object obj )
	{
		if(!(obj instanceof SleepRecord))
			return false;
		
		SleepRecord record = (SleepRecord)obj;
		
		return (mIsSleeping == record.mIsSleeping && mLocation.equals(record.mLocation));
	}
}
