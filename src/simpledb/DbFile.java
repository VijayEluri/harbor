package simpledb;

import java.util.*;
import java.io.*;

/**
 * The interface for database files on disk .
 * Database files need to be able to fetch pages and iterate through tuples.
 * They may also support sargable predictes for efficient index searches.
 *
 * DbFile's are generally access through the buffer pool (rather than
 * directly by operators.)
 */
public interface DbFile {
  /**
   * Read the specified page from disk. 
   * @throws NoSuchElementException if the page does not exist in this file
   */
  public Page readPage(PageId id) throws NoSuchElementException;
  
  /** Return the number of pages in this file */
  public int numPages();
  
  /**
   * Push the specified page to disk.
   * This page must have been previously read from this file via a call to
   * readPage. 
   * @throws IOException if the write fails
   *       
   */
  public void writePage(Page p) throws IOException;
  
  /**
   * Adds the specified tuple to the file on behalf of transaction.
   * This method will acquire a lock on the affected pages of the file, and
   * may block until the lock can be acquired.
   * @returns RecordId the RecordId assigned to the new tuple
   * @param tid The transaction performing the update
   * @param t The tuple to add.  This tuple will be updated to reflect that
   *          it is now stored in this file.
   * @throws DbException if the tuple cannot be added
   * @throws IOException if the needed file can't be read/written
   */
  public RecordId addTuple(TransactionId tid, Tuple t) throws DbException, IOException, TransactionAbortedException;
      
  /**
   * Removes the specifed tuple from the file on behalf of the specified
   * transaction.
   * This method will acquire a lock on the affected pages of the file, and
   * may block until the lock can be acquired.
   * @throws DbException if the tuple cannot be deleted or is not a member of the file
   */
  public Page deleteTuple(TransactionId tid, Tuple t) throws DbException, TransactionAbortedException;
  
  /**
   * Updates the tuple specified by rid from the file on behalf of the specified
   * transaction to func(tuple).
   * This method will acquire a lock on the affected pages of the file, and
   * may block until the lock can be acquired.
   * @throws DbException if the tuple cannot be updated or is not a member of the file
   */
  public Page updateTuple(TransactionId tid, RecordId rid, UpdateFunction func) throws DbException, TransactionAbortedException;

  /**
   * Returns a table id for this table.
   */
  public int id();
}
