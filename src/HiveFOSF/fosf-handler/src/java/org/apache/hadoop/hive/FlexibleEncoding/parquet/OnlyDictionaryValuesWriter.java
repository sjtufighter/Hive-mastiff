package org.apache.hadoop.hive.mastiffFlexibleEncoding.parquet;

/*
 * adapted  by wangmeng
 */


import it.unimi.dsi.fastutil.doubles.Double2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.doubles.Double2IntMap;
import it.unimi.dsi.fastutil.doubles.DoubleIterator;
import it.unimi.dsi.fastutil.floats.Float2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.floats.Float2IntMap;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;

import org.apache.hadoop.io.DataOutputBuffer;

//RunLengthBitPackingHybridEncoder
public abstract class OnlyDictionaryValuesWriter extends ValuesWriter {
  //  public class PlainIntegerDictionaryValuesWriter {
  //
  //  }

  private static final Log LOG = Log.getLog(OnlyDictionaryValuesWriter.class);

  /* max entries allowed for the dictionary will fail over to plain encoding if reached */
  private static final int MAX_DICTIONARY_ENTRIES = Integer.MAX_VALUE - 1;

  /* maximum size in bytes allowed for the dictionary will fail over to plain encoding if reached */
  protected final int maxDictionaryByteSize;

  /* contains the values encoded in plain if the dictionary grows too big */
  protected final PlainValuesWriter plainValuesWriter;

  /* will become true if the dictionary becomes too big */
  protected boolean dictionaryTooBig;

  /* current size in bytes the dictionary will take once serialized */
  protected int dictionaryByteSize;

  /* size in bytes of the dictionary at the end of last dictionary encoded page (in case the current page falls back to PLAIN) */
  protected int lastUsedDictionaryByteSize;

  /* size in items of the dictionary at the end of last dictionary encoded page (in case the current page falls back to PLAIN) */
  protected int lastUsedDictionarySize;

  /* dictionary encoded values */
  protected IntList encodedValues = new IntList();

  /* size of raw data, even if dictionary is used, it will not have effect on raw data size, it is used to decide
   * if fall back to plain encoding is better by comparing rawDataByteSize with Encoded data size
   * It's also used in getBufferedSize, so the page will be written based on raw data size
   */
  protected long rawDataByteSize = 0;

  /** indicates if this is the first page being processed */
  protected boolean firstPage = true;

  /**
   * @param maxDictionaryByteSize
   * @param initialSize
   */
  protected OnlyDictionaryValuesWriter(int maxDictionaryByteSize, int initialSize) {
    this.maxDictionaryByteSize = maxDictionaryByteSize;
    this.plainValuesWriter = new PlainValuesWriter(initialSize);
  }

  protected abstract void fallBackDictionaryEncodedData();

  @Override
  public long getBufferedSize() {
    // use raw data size to decide if we want to flush the page
    // so the acutual size of the page written could be much more smaller
    // due to dictionary encoding. This prevents page being to big when fallback happens.
    return rawDataByteSize;
  }

  @Override
  public long getAllocatedSize() {
    // size used in memory
    return encodedValues.size() * 4 + plainValuesWriter.getAllocatedSize();
  }

  @Override
  public BytesInput getBytes() {
    //  long tmp=System.currentTimeMillis() ;
    //if (!dictionaryTooBig && getDictionarySize() > 0) {
    int maxDicId = getDictionarySize() - 1;
    if (Log.DEBUG) {
      LOG.debug("max dic id " + maxDicId);
    }
    int bitWidth = BytesUtils.getWidthFromMaxInt(maxDicId);

    // TODO: what is a good initialCapacity?
    RunLengthBitPackingHybridEncoder encoder = new RunLengthBitPackingHybridEncoder(bitWidth, 64 * 1024);
    IntList.IntIterator  iterator = encodedValues.iterator();
    try {
      while (iterator.hasNext()) {
        encoder.writeInt(iterator.next());
      }
      // encodes the bit width
      byte[] bytesHeader = new byte[] { (byte) bitWidth };
      BytesInput rleEncodedBytes = encoder.toBytes();
      if (Log.DEBUG) {
        LOG.debug("rle encoded bytes " + rleEncodedBytes.size());
      }
      BytesInput bytes = BytesInput.concat(BytesInput.from(bytesHeader), rleEncodedBytes);
      //        if (firstPage && ((bytes.size() + dictionaryByteSize) > rawDataByteSize)) {
      //          fallBackToPlainEncoding();
      //        } else {
      // remember size of dictionary when we last wrote a page
      lastUsedDictionarySize = getDictionarySize();
      lastUsedDictionaryByteSize = dictionaryByteSize;
      //  System.out.println("time1  "+(System.currentTimeMillis()-tmp));
      //  System.out.println("bytes  "+bytes);
      return bytes;
      //  }
    } catch (IOException e) {
      throw new ParquetEncodingException("could not encode the values", e);
    }
    //}

    //return plainValuesWriter.getBytes();
  }

  @Override
  public Encoding getEncoding() {
    firstPage = false;
    if (!dictionaryTooBig && getDictionarySize() > 0) {
      return Encoding.PLAIN_DICTIONARY;
    }
    return plainValuesWriter.getEncoding();
  }

  @Override
  public void reset() {
    encodedValues = new IntList();
    plainValuesWriter.reset();
    rawDataByteSize = 0;
  }

  @Override
  public void resetDictionary() {
    lastUsedDictionaryByteSize = 0;
    lastUsedDictionarySize = 0;
    dictionaryTooBig = false;
    clearDictionaryContent();
  }

  /**
   * clear/free the underlying dictionary content
   */
  protected abstract void clearDictionaryContent();

  /**
   * @return size in items
   */
  protected abstract int getDictionarySize();

  @Override
  public String memUsageString(String prefix) {
    return String.format(
        "%s  OnlyDictionaryValuesWriter{\n%s\n%s\n%s\n%s}\n",
        prefix,
        plainValuesWriter.
        memUsageString(prefix + " plain:"),
        prefix + " dict:" + dictionaryByteSize,
        prefix + " values:" + String.valueOf(encodedValues.size() * 4),
        prefix
        );
  }

  /**
   *
   */
  public static class PlainBinaryDictionaryValuesWriter extends  OnlyDictionaryValuesWriter {

    /* type specific dictionary content */
    private final Object2IntMap<Binary> binaryDictionaryContent = new Object2IntLinkedOpenHashMap<Binary>();

    /**
     * @param maxDictionaryByteSize
     * @param initialSize
     */
    public PlainBinaryDictionaryValuesWriter(int maxDictionaryByteSize, int initialSize) {
      super(maxDictionaryByteSize, initialSize);
      binaryDictionaryContent.defaultReturnValue(-1);
    }


    @Override
    public void writeBytes(Binary v) {
      //  if (!dictionaryTooBig) {
      int id = binaryDictionaryContent.getInt(v);
      if (id == -1) {
        id = binaryDictionaryContent.size();
        binaryDictionaryContent.put(v, id);
        // length as int (4 bytes) + actual bytes
        dictionaryByteSize += 4 + v.length();
      }
      encodedValues.add(id);
      //     checkAndFallbackIfNeeded();
      //} else {
      plainValuesWriter.writeBytes(v);
      //}
      //for rawdata, length(4 bytes int) is stored, followed by the binary content itself
      //rawDataByteSize += v.length() + 4;
    }

    @Override
    public DictionaryPage createDictionaryPage() {
      if (lastUsedDictionarySize > 0) {
        // return a dictionary only if we actually used it
        PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
        Iterator<Binary> binaryIterator = binaryDictionaryContent.keySet().iterator();
        // write only the part of the dict that we used
        for (int i = 0; i < lastUsedDictionarySize; i++) {
          Binary entry = binaryIterator.next();
          dictionaryEncoder.writeBytes(entry);
        }
        return new DictionaryPage(dictionaryEncoder.getBytes(), lastUsedDictionarySize, Encoding.PLAIN_DICTIONARY);
      }
      return plainValuesWriter.createDictionaryPage();
    }

    @Override
    public int getDictionarySize() {
      return binaryDictionaryContent.size();
    }

    @Override
    protected void clearDictionaryContent() {
      binaryDictionaryContent.clear();
    }

    @Override
    protected void fallBackDictionaryEncodedData() {
      //build reverse dictionary
      Binary[] reverseDictionary = new Binary[getDictionarySize()];
      ObjectIterator<Object2IntMap.Entry<Binary>> entryIterator = binaryDictionaryContent.object2IntEntrySet().iterator();
      while (entryIterator.hasNext()) {
        Object2IntMap.Entry<Binary> entry = entryIterator.next();
        reverseDictionary[entry.getIntValue()] = entry.getKey();
      }

      //fall back to plain encoding
      IntList.IntIterator iterator = encodedValues.iterator();
      while (iterator.hasNext()) {
        int id = iterator.next();
        plainValuesWriter.writeBytes(reverseDictionary[id]);
      }
    }

    //added  by wm
    public  long  WriteDictionaryToDisk(String[] s) throws IOException{
      long tmp1=System.currentTimeMillis();
      // return a dictionary only if we actually used it
      PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
      Iterator<Binary> binaryIterator = binaryDictionaryContent.keySet().iterator();
      // write only the part of the dict that we used
      for (int i = 0; i < lastUsedDictionarySize; i++) {
        Binary entry = binaryIterator.next();
        dictionaryEncoder.writeBytes(entry);
      }
      long tmp2=System.currentTimeMillis();
      //  System.out.println("lastUsedDictionarySize  "+lastUsedDictionarySize);
      //  return new DictionaryPage(dictionaryEncoder.getBytes(), lastUsedDictionarySize, Encoding.PLAIN_DICTIONARY);

      DataOutputStream  dos =new DataOutputStream(new FileOutputStream(new File(s[3])));
      dos.writeInt(lastUsedDictionarySize);
      dos.write(dictionaryEncoder.getBytes().toByteArray());
      //  dictionaryEncoder.getBytes().writeAllTo(dos);
      //dos.writeInt(dictionaryEncoder.getBytes().toByteArray().length);

      dos.close();
      return tmp2-tmp1 ;
    }
    public byte[]  getDictionaryBuffer() throws IOException{
      DataOutputBuffer    dob=new  DataOutputBuffer()  ;
      // return a dictionary only if we actually used it
      PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
      Iterator<Binary> binaryIterator = binaryDictionaryContent.keySet().iterator();
      // write only the part of the dict that we used
      for (int i = 0; i < lastUsedDictionarySize; i++) {
        Binary entry = binaryIterator.next();
        dictionaryEncoder.writeBytes(entry);
      }
      long tmp2=System.currentTimeMillis();
      //  System.out.println("lastUsedDictionarySize  "+lastUsedDictionarySize);
      //  return new DictionaryPage(dictionaryEncoder.getBytes(), lastUsedDictionarySize, Encoding.PLAIN_DICTIONARY);
    //  dos.writeInt(lastUsedDictionarySize);
     // dos.write(dictionaryEncoder.getBytes().toByteArray());
      dob.writeInt(lastUsedDictionarySize);
      dob.write(dictionaryEncoder.getBytes().toByteArray(), 0, dictionaryEncoder.getBytes().toByteArray().length);
       return  dob.getData();


    }
  }



  /**
   *
   */
  public static class PlainLongDictionaryValuesWriter extends  OnlyDictionaryValuesWriter {

    /* type specific dictionary content */
    private final Long2IntMap longDictionaryContent = new Long2IntLinkedOpenHashMap();

    /**
     * @param maxDictionaryByteSize
     * @param initialSize
     */
    public PlainLongDictionaryValuesWriter(int maxDictionaryByteSize, int initialSize) {
      super(maxDictionaryByteSize, initialSize);
      longDictionaryContent.defaultReturnValue(-1);
    }

    @Override
    public void writeLong(long v) {
      if (!dictionaryTooBig) {
        int id = longDictionaryContent.get(v);
        if (id == -1) {
          id = longDictionaryContent.size();
          longDictionaryContent.put(v, id);
          dictionaryByteSize += 8;
        }
        encodedValues.add(id);
        //       checkAndFallbackIfNeeded();
      } else {
        plainValuesWriter.writeLong(v);
      }
      rawDataByteSize += 8;
    }

    @Override
    public DictionaryPage createDictionaryPage() {
      if (lastUsedDictionarySize > 0) {
        // return a dictionary only if we actually used it
        PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
        LongIterator longIterator = longDictionaryContent.keySet().iterator();
        // write only the part of the dict that we used
        for (int i = 0; i < lastUsedDictionarySize; i++) {
          dictionaryEncoder.writeLong(longIterator.nextLong());
        }
        return new DictionaryPage(dictionaryEncoder.getBytes(), lastUsedDictionarySize, Encoding.PLAIN_DICTIONARY);
      }
      return plainValuesWriter.createDictionaryPage();
    }

    @Override
    public int getDictionarySize() {
      return longDictionaryContent.size();
    }

    @Override
    protected void clearDictionaryContent() {
      longDictionaryContent.clear();
    }

    @Override
    protected void fallBackDictionaryEncodedData() {
      //build reverse dictionary
      long[] reverseDictionary = new long[getDictionarySize()];
      ObjectIterator<Long2IntMap.Entry> entryIterator = longDictionaryContent.long2IntEntrySet().iterator();
      while (entryIterator.hasNext()) {
        Long2IntMap.Entry entry = entryIterator.next();
        reverseDictionary[entry.getIntValue()] = entry.getLongKey();
      }

      //fall back to plain encoding
      IntList.IntIterator  iterator = encodedValues.iterator();
      while (iterator.hasNext()) {
        int id = iterator.next();
        plainValuesWriter.writeLong(reverseDictionary[id]);
      }
    }
  }

  /**
   *
   */
  public static class PlainDoubleDictionaryValuesWriter extends  OnlyDictionaryValuesWriter {

    /* type specific dictionary content */
    private final Double2IntMap doubleDictionaryContent = new Double2IntLinkedOpenHashMap();

    /**
     * @param maxDictionaryByteSize
     * @param initialSize
     */
    public PlainDoubleDictionaryValuesWriter(int maxDictionaryByteSize, int initialSize) {
      super(maxDictionaryByteSize, initialSize);
      doubleDictionaryContent.defaultReturnValue(-1);
    }

    @Override
    public void writeDouble(double v) {
      if (!dictionaryTooBig) {
        int id = doubleDictionaryContent.get(v);
        if (id == -1) {
          id = doubleDictionaryContent.size();
          doubleDictionaryContent.put(v, id);
          dictionaryByteSize += 8;
        }
        encodedValues.add(id);
        //     checkAndFallbackIfNeeded();
      } else {
        plainValuesWriter.writeDouble(v);
      }
      rawDataByteSize += 8;
    }

    @Override
    public DictionaryPage createDictionaryPage() {
      if (lastUsedDictionarySize > 0) {
        // return a dictionary only if we actually used it
        PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
        DoubleIterator doubleIterator = doubleDictionaryContent.keySet().iterator();
        // write only the part of the dict that we used
        for (int i = 0; i < lastUsedDictionarySize; i++) {
          dictionaryEncoder.writeDouble(doubleIterator.nextDouble());
        }
        return new DictionaryPage(dictionaryEncoder.getBytes(), lastUsedDictionarySize, Encoding.PLAIN_DICTIONARY);
      }
      return plainValuesWriter.createDictionaryPage();
    }

    @Override
    public int getDictionarySize() {
      return doubleDictionaryContent.size();
    }

    @Override
    protected void clearDictionaryContent() {
      doubleDictionaryContent.clear();
    }

    @Override
    protected void fallBackDictionaryEncodedData() {
      //build reverse dictionary
      double[] reverseDictionary = new double[getDictionarySize()];
      ObjectIterator<Double2IntMap.Entry> entryIterator = doubleDictionaryContent.double2IntEntrySet().iterator();
      while (entryIterator.hasNext()) {
        Double2IntMap.Entry entry = entryIterator.next();
        reverseDictionary[entry.getIntValue()] = entry.getDoubleKey();
      }

      //fall back to plain encoding
      IntList.IntIterator  iterator = encodedValues.iterator();
      while (iterator.hasNext()) {
        int id = iterator.next();
        plainValuesWriter.writeDouble(reverseDictionary[id]);
      }
    }
  }

  /**
   *
   */
  public static class PlainIntegerDictionaryValuesWriter extends OnlyDictionaryValuesWriter {

    /* type specific dictionary content */
    private final Int2IntMap intDictionaryContent = new Int2IntLinkedOpenHashMap();

    /**
     * @param maxDictionaryByteSize
     * @param initialSize
     */
    public PlainIntegerDictionaryValuesWriter(int maxDictionaryByteSize, int initialSize) {
      super(maxDictionaryByteSize, initialSize);
      intDictionaryContent.defaultReturnValue(-1);
    }

    @Override
    public void writeInteger(int v) {
      //if (!dictionaryTooBig) {
      int id = intDictionaryContent.get(v);
      if (id == -1) {
        id = intDictionaryContent.size();
        intDictionaryContent.put(v, id);
        dictionaryByteSize += 4;
      }
      encodedValues.add(id);
      //    checkAndFallbackIfNeeded();
      //      } else {
      //        plainValuesWriter.writeInteger(v);
      //      }

      //Each integer takes 4 bytes as raw data(plain encoding)
      //rawDataByteSize += 4;
    }
    //a page data  wm
    @Override
    public DictionaryPage createDictionaryPage() {
      if (lastUsedDictionarySize > 0) {
        // return a dictionary only if we actually used it
        PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
        it.unimi.dsi.fastutil.ints.IntIterator intIterator = intDictionaryContent.keySet().iterator();
        // write only the part of the dict that we used
        for (int i = 0; i < lastUsedDictionarySize; i++) {
          dictionaryEncoder.writeInteger(intIterator.nextInt());
        }
        System.out.println("dictionaryEncoder.getBytes()  "+dictionaryEncoder.getBytes());
        //CapacityBAOSBytesInput
        return new DictionaryPage(dictionaryEncoder.getBytes(), lastUsedDictionarySize, Encoding.PLAIN_DICTIONARY);
      }
      return plainValuesWriter.createDictionaryPage();
    }
    //added  by wm
    public long WriteDictionaryToDisk(String[] s) throws IOException{
      long  tmp=System.currentTimeMillis() ;

      // return a dictionary only if we actually used it
      PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
      it.unimi.dsi.fastutil.ints.IntIterator intIterator = intDictionaryContent.keySet().iterator();
      // write only the part of the dict that we used
      for (int i = 0; i < lastUsedDictionarySize; i++) {
        dictionaryEncoder.writeInteger(intIterator.nextInt());
      }
      long tmp1=System.currentTimeMillis();
      //  System.out.println("lastUsedDictionarySize  "+lastUsedDictionarySize);
      //    //  return new DictionaryPage(dictionaryEncoder.getBytes(), lastUsedDictionarySize, Encoding.PLAIN_DICTIONARY);
      //      long tmp2=System.currentTimeMillis();
      //   System.out.println("tmp1  "+(tmp2-tmp));
      DataOutputStream  dos =new DataOutputStream(new FileOutputStream(new File(s[3])));
      dos.writeInt(lastUsedDictionarySize);
      dos.write(dictionaryEncoder.getBytes().toByteArray());
      //  dictionaryEncoder.getBytes().writeAllTo(dos);
      //dos.writeInt(dictionaryEncoder.getBytes().toByteArray().length);

      //System.out.println("tmp2  "+(tmp3-tmp));
      dos.close();
      return (tmp1-tmp) ;

      //System.out.println("tmp3  "+(System.currentTimeMillis()-tmp));
    }

    public byte[]  getDictionaryBuffer() throws IOException{
      DataOutputBuffer    dob=new  DataOutputBuffer()  ;

      // return a dictionary only if we actually used it
      PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
      it.unimi.dsi.fastutil.ints.IntIterator intIterator = intDictionaryContent.keySet().iterator();
      // write only the part of the dict that we used
      for (int i = 0; i < lastUsedDictionarySize; i++) {
        dictionaryEncoder.writeInteger(intIterator.nextInt());
      }
      dob.writeInt(lastUsedDictionarySize);
      dob.write(dictionaryEncoder.getBytes().toByteArray(), 0, dictionaryEncoder.getBytes().toByteArray().length);
       return  dob.getData();


    }


    @Override
    public int getDictionarySize() {
      return intDictionaryContent.size();
    }

    @Override
    protected void clearDictionaryContent() {
      intDictionaryContent.clear();
    }

    @Override
    protected void fallBackDictionaryEncodedData() {
      //build reverse dictionary
      int[] reverseDictionary = new int[getDictionarySize()];
      ObjectIterator<Int2IntMap.Entry> entryIterator = intDictionaryContent.int2IntEntrySet().iterator();
      while (entryIterator.hasNext()) {
        Int2IntMap.Entry entry = entryIterator.next();
        reverseDictionary[entry.getIntValue()] = entry.getIntKey();
      }

      //fall back to plain encoding
      IntList.IntIterator iterator = encodedValues.iterator();
      while (iterator.hasNext()) {
        int id = iterator.next();
        plainValuesWriter.writeInteger(reverseDictionary[id]);
      }
    }
  }

  /**
   *
   */
  public static class PlainFloatDictionaryValuesWriter extends OnlyDictionaryValuesWriter {

    /* type specific dictionary content */
    private final Float2IntMap floatDictionaryContent = new Float2IntLinkedOpenHashMap();

    /**
     * @param maxDictionaryByteSize
     * @param initialSize
     */
    public PlainFloatDictionaryValuesWriter(int maxDictionaryByteSize, int initialSize) {
      super(maxDictionaryByteSize, initialSize);
      floatDictionaryContent.defaultReturnValue(-1);
    }

    @Override
    public void writeFloat(float v) {
      if (!dictionaryTooBig) {
        int id = floatDictionaryContent.get(v);
        if (id == -1) {
          id = floatDictionaryContent.size();
          floatDictionaryContent.put(v, id);
          dictionaryByteSize += 4;
        }
        encodedValues.add(id);
        //    checkAndFallbackIfNeeded();
      } else {
        plainValuesWriter.writeFloat(v);
      }
      rawDataByteSize += 4;
    }

    @Override
    public DictionaryPage createDictionaryPage() {
      if (lastUsedDictionarySize > 0) {
        // return a dictionary only if we actually used it
        PlainValuesWriter dictionaryEncoder = new PlainValuesWriter(lastUsedDictionaryByteSize);
        FloatIterator floatIterator = floatDictionaryContent.keySet().iterator();
        // write only the part of the dict that we used
        for (int i = 0; i < lastUsedDictionarySize; i++) {
          dictionaryEncoder.writeFloat(floatIterator.nextFloat());
        }
        return new DictionaryPage(dictionaryEncoder.getBytes(), lastUsedDictionarySize, Encoding.PLAIN_DICTIONARY);
      }
      return plainValuesWriter.createDictionaryPage();
    }

    @Override
    public int getDictionarySize() {
      return floatDictionaryContent.size();
    }

    @Override
    protected void clearDictionaryContent() {
      floatDictionaryContent.clear();
    }

    @Override
    protected void fallBackDictionaryEncodedData() {
      //build reverse dictionary
      float[] reverseDictionary = new float[getDictionarySize()];
      ObjectIterator<Float2IntMap.Entry> entryIterator = floatDictionaryContent.float2IntEntrySet().iterator();
      while (entryIterator.hasNext()) {
        Float2IntMap.Entry entry = entryIterator.next();
        reverseDictionary[entry.getIntValue()] = entry.getFloatKey();
      }

      //fall back to plain encoding
      IntList.IntIterator  iterator = encodedValues.iterator();
      while (iterator.hasNext()) {
        int id = iterator.next();
        plainValuesWriter.writeFloat(reverseDictionary[id]);
      }
    }
  }

}
