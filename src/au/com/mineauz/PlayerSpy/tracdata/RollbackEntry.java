package au.com.mineauz.PlayerSpy.tracdata;

import java.io.IOException;
import java.io.RandomAccessFile;

public class RollbackEntry extends IndexEntry
{
	public static int cSize = 12;
	
	public int sessionId;
	public long detailLocation;
	public long detailSize;
	
	public void write(RandomAccessFile output) throws IOException
	{
		output.writeInt(sessionId);
		output.writeInt((int)detailLocation);
		output.writeInt((int)detailSize);
	}
	
	public void read(RandomAccessFile input) throws IOException
	{
		sessionId = input.readInt();
		detailLocation = input.readInt();
		detailSize = input.readInt();
	}
	
	@Override
	public long getLocation()
	{
		return detailLocation;
	}
	
	public long getSize()
	{
		return detailSize;
	}
}
