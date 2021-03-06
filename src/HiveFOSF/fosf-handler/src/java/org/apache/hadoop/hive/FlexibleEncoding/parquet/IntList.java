package org.apache.hadoop.hive.mastiffFlexibleEncoding.parquet;
/*
 * adapted  from Parquet*
 */

import java.util.ArrayList;
import java.util.List;

/**
 * An append-only integer list
 * avoids autoboxing and buffer resizing
 *
 *
 * @author Julien Le Dem
 *
 */
public class IntList {

  private static final int SLAB_SIZE = 64 * 1024;

  /**
   * to iterate on the content of the list
   * not an actual iterator to avoid autoboxing
   *
   * @author Julien Le Dem
   *
   */
  public static class IntIterator {

    private final int[][] slabs;
    private int current;
    private final int count;

    /**
     * slabs will be iterated in order up to the provided count
     * as the last slab may not be full
     * @param slabs contain the ints
     * @param count total count of ints
     */
    public IntIterator(int[][] slabs, int count) {
      this.slabs = slabs;
      this.count = count;
    }

    /**
     * @return wether there is a next value
     */
    public boolean hasNext() {
      return current < count;
    }

    /**
     * @return the next int
     */
    public int next() {
      final int result = slabs[current / SLAB_SIZE][current % SLAB_SIZE];
      ++ current;
      return result;
    }

  }

  private List<int[]> slabs = new ArrayList<int[]>();
  private int[] currentSlab;
  private int currentSlabPos;

  /**
   * construct an empty list
   */
  public IntList() {
    initSlab();
  }

  private void initSlab() {
    currentSlab = new int[SLAB_SIZE];
    currentSlabPos = 0;
  }

  /**
   * @param i value to append to the end of the list
   */
  public void add(int i) {
    if (currentSlabPos == currentSlab.length) {
      slabs.add(currentSlab);
      initSlab();
    }
    currentSlab[currentSlabPos] = i;
    ++ currentSlabPos;
  }

  /**
   * (not an actual Iterable)
   * @return an IntIterator on the content
   */
  public IntIterator iterator() {
    int[][] itSlabs = slabs.toArray(new int[slabs.size() + 1][]);
    itSlabs[slabs.size()] = currentSlab;
    return new IntIterator(itSlabs, SLAB_SIZE * slabs.size() + currentSlabPos);
  }

  /**
   * @return the current size of the list
   */
  public int size() {
    return SLAB_SIZE * slabs.size() + currentSlabPos;
  }

}