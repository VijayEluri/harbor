package simpledb;

import java.util.*;
import java.io.*;
import java.text.ParseException;

/**
 * HeapPage stores pages of HeapFiles and implements the Page interface that
 * is used by BufferPool.
 *
 * @see HeapFile
 * @see BufferPool
 */
public class HeapPage implements Page {

  PageId pid;
  TupleDesc td;
  DataInputStream dis;
  int header[];
  Tuple tuples[];
  int numSlots;
  boolean dirty;
  
  // invariant -- cursor points to next free slot (if any) after initialized = true
  int cursor;
  boolean initialized;
  
  // added for logging purposes
  long pageLsn;

  /**
   * Constructor.
   * Construct the HeapPage from a set of bytes of data read from disk.  The
   * format of a HeapPage is a set of 32-bit header words indicating the slots
   * of the page that are in use, plus (BufferPool.PAGE_SIZE/tuple size) tuple
   * slots, where tuple size is the size of tuples in this database table
   * (which can be determined via a call to getTupleDesc in Catalog.)
   * <p>
   * The number of 32-bitheader words is equal to:
   * <p>
   * (no. tuple slots / 32) + 1
   * <p>
   * @see Catalog#Instance
   * @see Catalog#getTupleDesc
   * @see BufferPool#PAGE_SIZE
   */
  // 32 tuples => 2 headers, with last unused
  // 33 tuples => 2 headers, with first bit of 2nd header used
  public HeapPage(PageId id, byte[] data) throws ParseException, IOException {
    this.pid = id;
    this.td = Catalog.Instance().getTupleDesc(id.tableid());
    this.numSlots = BufferPool.PAGE_SIZE / td.getSize();
    this.dis = new DataInputStream(new ByteArrayInputStream(data));

	pageLsn = dis.readLong();

    // allocate and read the header slots of this page
    header = new int[(numSlots/32)+1]; // 32 bits per integer
    for(int i=0; i<header.length; i++)
      header[i] = dis.readInt();

    // allocate and read the actual records of this page
    tuples = new Tuple[numSlots];
    for(int i=0; i<numSlots; i++)
      tuples[i] = readNextTuple(i);
  }

  public PageId id() {
    // some code goes here
    return pid;
  }
  
  public synchronized long pageLsn() {
	return pageLsn;
  }
  
  public synchronized void setPageLsn(long lsn) {
	pageLsn = lsn;
  }

  // private method to suck up tuples from the source file.
  private synchronized Tuple readNextTuple(int slotId) throws NoSuchElementException {

    // if associated bit is not set, read forward to the next tuple, and
    // return null.
    if(!getSlot(slotId)) {
      for(int i=0; i<td.getSize(); i++) {
        try {
          dis.readByte();
        } catch (IOException e) {
          throw new NoSuchElementException();
        }
      }
      return null;
    }

    // read fields in the tuple
    Tuple t = new Tuple(td);
    RecordId rid = new RecordId(pid.tableid(), pid.pageno(), slotId);
    t.setRecordId(rid);
    try {
      for (int j=0; j<td.numFields(); j++) {
        Field f = td.getType(j).parse(dis);
        t.setField(j, f);
      }	
    } catch (java.text.ParseException e) {
      e.printStackTrace();
      throw new NoSuchElementException();
    }	

    return t;
  }

  public synchronized Iterator iterator() {
    // some code goes here
	final HeapPage page = this;
	
    return new Iterator<Tuple>() {
    	int pos = findNextNonEmptySlot(0);
    	
    	public boolean hasNext() {
			synchronized (page) {
				return (pos < numSlots);
			}
    	}
    	
    	public Tuple next() {
			synchronized (page) {
				if (pos > numSlots) {
					throw new NoSuchElementException("no more elements in this page");
				}
				if (!getSlot(pos)) {
					throw new ConcurrentModificationException("slot should have something but doesn't");
				}
				Tuple t = tuples[pos];
				t.setRecordId(new RecordId(pid.tableid(), pid.pageno(), pos));
				pos = findNextNonEmptySlot(pos + 1);
				return t;
			}
    	}
    	
    	public void remove() {
    		throw new UnsupportedOperationException();
    	}
    };
  }

  /**
   * Generates a byte array representing the contents of this page.
   * Used to serialize this page to disk.
   * <p>
   * The invariant here is that it should be possible to pass the byte array
   * generated by getPageData to the HeapPage constructor and have it produce
   * an identical HeapPage object.
   *
   *
   * @see #HeapPage
   * @return A byte array correspond to the bytes of this page.
   */
  public synchronized byte[] getPageData() {
    int len = header.length*4 + BufferPool.PAGE_SIZE + 8;
    ByteArrayOutputStream baos = new ByteArrayOutputStream(len);
    DataOutputStream dos = new DataOutputStream(baos);

	try {
		dos.writeLong(pageLsn);
	} catch (IOException e) {
		e.printStackTrace();
	}

    // create the header of the page
    for(int i=0; i<header.length; i++) {
      try {
        dos.writeInt(header[i]);
      } catch (IOException e) {
        // this really shouldn't happen
        e.printStackTrace();
      }
    }

    // create the tuples
    for(int i=0; i<numSlots; i++) {

      // empty slot
      if(!getSlot(i)) {
        for (int j=0; j<td.getSize(); j++) {
          try {
            dos.writeByte(0);
          } catch(IOException e) {
            e.printStackTrace();
          }
        }
        continue;
      }

      // non-empty slot
      for (int j=0; j<td.numFields(); j++) {
        Field f = tuples[i].getField(j);
        try {
          f.serialize(dos);
        } catch(IOException e) {
          e.printStackTrace();
        }
      }
    }
    
    int remainingSpace = BufferPool.PAGE_SIZE - numSlots * td.getSize();
    for (int k = 0; k < remainingSpace; k++) {
      try {
	      dos.writeByte(0);
	    } catch(IOException e) {
	      e.printStackTrace();
	    }
    }

    try {
      dos.flush();
    } catch(IOException e) {
      e.printStackTrace();
    }
    return baos.toByteArray();
  }

  /**
   * Static method to generate a byte array corresponding to an empty
   * HeapPage.  Used to add new, empty pages to the file. Passing the results
   * of this method to the HeapPage constructor will create a HeapPage with no
   * valid tuples in it.

   * @param tableid The id of the table that this empty page will belong to.
   * @return The returned ByteArray.
   */
  public static byte[] createEmptyPageData(int tableid) {
    TupleDesc td = Catalog.Instance().getTupleDesc(tableid);
    int hb = (((BufferPool.PAGE_SIZE / td.getSize()) / 32) +1) * 4;
    int len = BufferPool.PAGE_SIZE + hb + 8; // + 8 for pageLsn
    return new byte[len]; //all 0
  }

  //reset the position to the beginning of the page
  public void rewind() {
    // some code goes here
  	throw new UnsupportedOperationException();
  }
  
  /**
   * Delete the specified tuple from the page.
   * @throws DbException if this tuple is not on this page, or tuple slot is
   * already empty.
   * @param t The tuple to delete
   */
  // XXX Changed spec for from boolean to void
  public synchronized void deleteTuple(Tuple t) throws DbException {
    // some code goes here
  	RecordId rid = t.getRecordId();
  	assert(rid.tableid() == pid.tableid() && rid.pageno() == pid.pageno());
  	
  	int slotId = rid.tupleno();
  	if (!getSlot(slotId)) {
  		throw new DbException("Tried to delete tuple " + t + " from already empty slot");
  	}
//  	if (!tuples[slotId].equals(t)) {
//  		throw new DbException("Tried to delete tuple " + t + ", but it's not in the page");
//  	}
  	setHeaderBit(slotId, false);
  	if (slotId < cursor) {
  		cursor = slotId;
  	}
  }

  // Adds the tuple to the page at the slot specifed by rid.
  // To be used for recovery purposes only.
  // @throws DbException if tuple already exists
  public synchronized void addTupleFromLog(RecordId rid, Tuple t) throws DbException {
  	assert(rid.tableid() == pid.tableid() && rid.pageno() == pid.pageno());
	int slotId = rid.tupleno();
	
	if (getSlot(slotId)) {
		System.out.println(rid + ": " + t);
		throw new DbException("Tried to add tuple to already filled slot");
	}
	tuples[slotId] = t;
	setHeaderBit(slotId, true);
  }

  /**
   * Adds the specified tuple to the page.
   * @throws DbException if the page is full (no empty slots).
   * @param t The tuple to add.
   */
  public synchronized RecordId addTuple(Tuple t) throws DbException {
		// prevent rep exposure by making new copy
  	// plus, we don't want to be mutating the rid of the tuple which is in some other operator
  	Tuple tuple = new Tuple(t);
    // some code goes here
  	if (!initialized) {
  		cursor = findNextEmptySlot(0);
  		initialized = true;
  	}
  	if (cursor >= numSlots) {
  		throw new DbException("HeapPage is full");
  	} else {
  		// prevent rep exposure by making new copy
  		tuples[cursor] = tuple;

      RecordId rid = new RecordId(pid.tableid(), pid.pageno(), cursor);
  		tuple.setRecordId(rid);
  		setHeaderBit(cursor, true);
  		cursor = findNextEmptySlot(cursor + 1);
		return rid;
  	}
  }
  
  /**
   * Updates the tuple identified by rid.
   * @returns the old tuple
   */
  public synchronized Tuple updateTuple(RecordId rid, Tuple t) throws DbException {
  	assert(rid.tableid() == pid.tableid() && rid.pageno() == pid.pageno());
	int slotId = rid.tupleno();
	if (!getSlot(slotId)) {
  		throw new DbException("Tried to update tuple " + rid + " in empty slot");
  	}
	Tuple oldTuple = tuples[slotId];
	if (!(t.getTupleDesc().equals(td))) {
		throw new DbException("Tried to modify tuple desc during update of " + rid);
	}
	tuples[slotId] = t;
	return oldTuple;
  }
  
  /**
   * Updates the tuple identified by rid with func(tuple).
   * @returns the old tuple
   */
  public synchronized Tuple updateTuple(RecordId rid, UpdateFunction func) throws DbException {
  	assert(rid.tableid() == pid.tableid() && rid.pageno() == pid.pageno());
	int slotId = rid.tupleno();
	if (!getSlot(slotId)) {
  		throw new DbException("Tried to update tuple " + rid + " in empty slot");
  	}
	return updateTuple(rid, func.update(tuples[slotId]));
  }
  
  // only to be used by recovery
  public synchronized void updateTuple(RecordId rid, int fieldIndex, Field value) throws DbException {
	assert(rid.tableid() == pid.tableid() && rid.pageno() == pid.pageno());
	int slotId = rid.tupleno();
	if (!getSlot(slotId)) {
  		throw new DbException("Tried to update tuple " + rid + " in empty slot");		
	}
	Tuple tuple = tuples[slotId];
	tuple.setField(fieldIndex, value);
  }

  /**
   * Marks this page as dirty/not dirty.
   */
  public synchronized void markDirty(boolean dirty) {
    // some code goes here
  	this.dirty = dirty;
  }

  /**
   * Returns whether or not this page is dirty.
   */
  public synchronized boolean isDirty() {
    // some code goes here
    return dirty;
  }

  /**
   * Returns the number of emtpy slots on this page.
   */
  public synchronized int getNumEmptySlots() {
    // some code goes here
  	int numEmptySlots = 0;
  	for (int i = 0; i < header.length; i++) {
  		for (int j = 0; j < 32; j++) {
  			if ((i == header.length - 1) && (j >= numSlots % 32))
  				break;
  			else if (((header[i] >> j) & 0x1) == 0)
  				numEmptySlots++;
  		}
  	}
    return numEmptySlots;
  }

  /**
   * Returns true if associated slot on this page is filled.
   */
  private boolean getSlot(int i) {
    // some code goes here
  	if (i >= numSlots)
  		throw new IllegalArgumentException("i >= numSlots, which is 0-indexed");
  	// low bits come first
  	int slotHeader = header[(i / 32)];
  	int index = i % 32;
  	int slot = (slotHeader >> index) & 0x1;
  	return (slot != 0);
  }

  /**
   * Sets the ith slot in the header to 1 or 0 based on whether bit is
   * true or false, respectively.
   * @param i
   * @param bit
   */
  private void setHeaderBit(int i, boolean bit) {
  	int headerIndex = i / 32;
  	int bitIndex = i % 32;
  	if (bit) {
  		header[headerIndex] |= (0x1 << bitIndex);
  	} else {
  		header[headerIndex] &= ~(0x1 << bitIndex);
  	}
  }
  
  private int findNextNonEmptySlot(int cursor) {
  	return findNextSlot(cursor, true);
  }
  
  private int findNextEmptySlot(int cursor) {
  	return findNextSlot(cursor, false);
  }
  
  private int findNextSlot(int cursor, boolean filled) {
  	while (cursor < numSlots) {
  		if (filled != getSlot(cursor)) { // caller specifies "empty" but slot is filled
  			cursor++;
  		} else {
  			break;
  		}
  	}
  	return cursor;
  }
}

