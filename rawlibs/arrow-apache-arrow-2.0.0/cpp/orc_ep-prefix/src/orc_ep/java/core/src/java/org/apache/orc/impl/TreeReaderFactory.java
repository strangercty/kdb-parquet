/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.orc.impl;

import java.io.EOFException;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.Decimal64ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DecimalColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ListColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.MapColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.StructColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.TimestampColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.UnionColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.StringExpr;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.orc.OrcFile;
import org.apache.orc.TypeDescription;
import org.apache.orc.OrcProto;
import org.apache.orc.impl.reader.ReaderEncryption;
import org.apache.orc.impl.reader.StripePlanner;
import org.apache.orc.impl.writer.TimestampTreeWriter;

/**
 * Factory for creating ORC tree readers.
 */
public class TreeReaderFactory {
  public interface Context {
    SchemaEvolution getSchemaEvolution();

    boolean isSkipCorrupt();

    boolean getUseUTCTimestamp();

    String getWriterTimezone();

    OrcFile.Version getFileFormat();

    ReaderEncryption getEncryption();
  }

  public static class ReaderContext implements Context {
    private SchemaEvolution evolution;
    private boolean skipCorrupt = false;
    private boolean useUTCTimestamp = false;
    private String writerTimezone;
    private OrcFile.Version fileFormat;
    private ReaderEncryption encryption;

    public ReaderContext setSchemaEvolution(SchemaEvolution evolution) {
      this.evolution = evolution;
      return this;
    }

    public ReaderContext setEncryption(ReaderEncryption value) {
      encryption = value;
      return this;
    }

    public ReaderContext skipCorrupt(boolean skipCorrupt) {
      this.skipCorrupt = skipCorrupt;
      return this;
    }

    public ReaderContext useUTCTimestamp(boolean useUTCTimestamp) {
      this.useUTCTimestamp = useUTCTimestamp;
      return this;
    }

    public ReaderContext writerTimeZone(String writerTimezone) {
      this.writerTimezone = writerTimezone;
      return this;
    }

    public ReaderContext fileFormat(OrcFile.Version version) {
      this.fileFormat = version;
      return this;
    }

    @Override
    public SchemaEvolution getSchemaEvolution() {
      return evolution;
    }

    @Override
    public boolean isSkipCorrupt() {
      return skipCorrupt;
    }

    @Override
    public boolean getUseUTCTimestamp() {
      return useUTCTimestamp;
    }

    @Override
    public String getWriterTimezone() {
      return writerTimezone;
    }

    @Override
    public OrcFile.Version getFileFormat() {
      return fileFormat;
    }

    @Override
    public ReaderEncryption getEncryption() {
      return encryption;
    }
  }

  public abstract static class TreeReader {
    protected final int columnId;
    protected BitFieldReader present = null;
    protected int vectorColumnCount;
    protected final Context context;

    TreeReader(int columnId, Context context) throws IOException {
      this(columnId, null, context);
    }

    protected TreeReader(int columnId, InStream in, Context context) throws IOException {
      this.columnId = columnId;
      this.context = context;
      if (in == null) {
        present = null;
      } else {
        present = new BitFieldReader(in);
      }
      vectorColumnCount = -1;
    }

    void setVectorColumnCount(int vectorColumnCount) {
      this.vectorColumnCount = vectorColumnCount;
    }

    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    protected static IntegerReader createIntegerReader(OrcProto.ColumnEncoding.Kind kind,
        InStream in,
        boolean signed,
        Context context) throws IOException {
      switch (kind) {
        case DIRECT_V2:
        case DICTIONARY_V2:
          return new RunLengthIntegerReaderV2(in, signed, context == null ? false : context.isSkipCorrupt());
        case DIRECT:
        case DICTIONARY:
          return new RunLengthIntegerReader(in, signed);
        default:
          throw new IllegalArgumentException("Unknown encoding " + kind);
      }
    }

    void startStripe(StripePlanner planner) throws IOException {
      checkEncoding(planner.getEncoding(columnId));
      InStream in = planner.getStream(new StreamName(columnId,
          OrcProto.Stream.Kind.PRESENT));
      if (in == null) {
        present = null;
      } else {
        present = new BitFieldReader(in);
      }
    }

    /**
     * Seek to the given position.
     *
     * @param index the indexes loaded from the file
     * @throws IOException
     */
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    public void seek(PositionProvider index) throws IOException {
      if (present != null) {
        present.seek(index);
      }
    }

    protected long countNonNulls(long rows) throws IOException {
      if (present != null) {
        long result = 0;
        for (long c = 0; c < rows; ++c) {
          if (present.next() == 1) {
            result += 1;
          }
        }
        return result;
      } else {
        return rows;
      }
    }

    abstract void skipRows(long rows) throws IOException;

    /**
     * Called at the top level to read into the given batch.
     * @param batch the batch to read into
     * @param batchSize the number of rows to read
     * @throws IOException
     */
    public void nextBatch(VectorizedRowBatch batch,
                          int batchSize) throws IOException {
      batch.cols[0].reset();
      batch.cols[0].ensureSize(batchSize, false);
      nextVector(batch.cols[0], null, batchSize);
    }

    /**
     * Populates the isNull vector array in the previousVector object based on
     * the present stream values. This function is called from all the child
     * readers, and they all set the values based on isNull field value.
     *
     * @param previous The columnVector object whose isNull value is populated
     * @param isNull Whether the each value was null at a higher level. If
     *               isNull is null, all values are non-null.
     * @param batchSize      Size of the column vector
     * @throws IOException
     */
    public void nextVector(ColumnVector previous,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (present != null || isNull != null) {
        // Set noNulls and isNull vector of the ColumnVector based on
        // present stream
        previous.noNulls = true;
        boolean allNull = true;
        for (int i = 0; i < batchSize; i++) {
          if (isNull == null || !isNull[i]) {
            if (present != null && present.next() != 1) {
              previous.noNulls = false;
              previous.isNull[i] = true;
            } else {
              previous.isNull[i] = false;
              allNull = false;
            }
          } else {
            previous.noNulls = false;
            previous.isNull[i] = true;
          }
        }
        previous.isRepeating = !previous.noNulls && allNull;
      } else {
        // There is no present stream, this means that all the values are
        // present.
        previous.noNulls = true;
        for (int i = 0; i < batchSize; i++) {
          previous.isNull[i] = false;
        }
      }
    }

    public BitFieldReader getPresent() {
      return present;
    }

    public int getColumnId() {
      return columnId;
    }
  }

  public static class NullTreeReader extends TreeReader {

    public NullTreeReader(int columnId) throws IOException {
      super(columnId, null);
    }

    @Override
    public void startStripe(StripePlanner planner) {
      // PASS
    }

    @Override
    void skipRows(long rows) {
      // PASS
    }

    @Override
    public void seek(PositionProvider position) {
      // PASS
    }

    @Override
    public void seek(PositionProvider[] position) {
      // PASS
    }

    @Override
    public void nextVector(ColumnVector vector, boolean[] isNull, int size) {
      vector.noNulls = false;
      vector.isNull[0] = true;
      vector.isRepeating = true;
    }
  }

  public static class BooleanTreeReader extends TreeReader {
    protected BitFieldReader reader = null;

    BooleanTreeReader(int columnId) throws IOException {
      this(columnId, null, null);
    }

    protected BooleanTreeReader(int columnId, InStream present, InStream data) throws IOException {
      super(columnId, present, null);
      if (data != null) {
        reader = new BitFieldReader(data);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      reader = new BitFieldReader(planner.getStream(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA)));
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      LongColumnVector result = (LongColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, batchSize);
    }
  }

  public static class ByteTreeReader extends TreeReader {
    protected RunLengthByteReader reader = null;

    ByteTreeReader(int columnId) throws IOException {
      this(columnId, null, null);
    }

    protected ByteTreeReader(int columnId, InStream present, InStream data) throws IOException {
      super(columnId, present, null);
      this.reader = new RunLengthByteReader(data);
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      reader = new RunLengthByteReader(planner.getStream(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA)));
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final LongColumnVector result = (LongColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, result.vector, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class ShortTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    ShortTreeReader(int columnId, Context context) throws IOException {
      this(columnId, null, null, null, context);
    }

    protected ShortTreeReader(int columnId, InStream present, InStream data,
        OrcProto.ColumnEncoding encoding, Context context)
        throws IOException {
      super(columnId, present, context);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.reader = createIntegerReader(encoding.getKind(), data, true, context);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(name), true, context);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final LongColumnVector result = (LongColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, result.vector, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class IntTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    IntTreeReader(int columnId, Context context) throws IOException {
      this(columnId, null, null, null, context);
    }

    protected IntTreeReader(int columnId, InStream present, InStream data,
        OrcProto.ColumnEncoding encoding, Context context)
        throws IOException {
      super(columnId, present, context);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.reader = createIntegerReader(encoding.getKind(), data, true, context);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(name), true, context);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final LongColumnVector result = (LongColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, result.vector, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class LongTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    LongTreeReader(int columnId, Context context) throws IOException {
      this(columnId, null, null, null, context);
    }

    protected LongTreeReader(int columnId, InStream present, InStream data,
        OrcProto.ColumnEncoding encoding,
        Context context)
        throws IOException {
      super(columnId, present, context);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.reader = createIntegerReader(encoding.getKind(), data, true, context);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(name), true, context);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final LongColumnVector result = (LongColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, result.vector, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class FloatTreeReader extends TreeReader {
    protected InStream stream;
    private final SerializationUtils utils;

    FloatTreeReader(int columnId) throws IOException {
      this(columnId, null, null);
    }

    protected FloatTreeReader(int columnId, InStream present, InStream data) throws IOException {
      super(columnId, present, null);
      this.utils = new SerializationUtils();
      this.stream = data;
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = planner.getStream(name);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      stream.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final DoubleColumnVector result = (DoubleColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      final boolean hasNulls = !result.noNulls;
      boolean allNulls = hasNulls;

      if (batchSize > 0) {
        if (hasNulls) {
          // conditions to ensure bounds checks skips
          for (int i = 0; batchSize <= result.isNull.length && i < batchSize; i++) {
            allNulls = allNulls & result.isNull[i];
          }
          if (allNulls) {
            result.vector[0] = Double.NaN;
            result.isRepeating = true;
          } else {
            // some nulls
            result.isRepeating = false;
            // conditions to ensure bounds checks skips
            for (int i = 0; batchSize <= result.isNull.length
                && batchSize <= result.vector.length && i < batchSize; i++) {
              if (!result.isNull[i]) {
                result.vector[i] = utils.readFloat(stream);
              } else {
                // If the value is not present then set NaN
                result.vector[i] = Double.NaN;
              }
            }
          }
        } else {
          // no nulls & > 1 row (check repeating)
          boolean repeating = (batchSize > 1);
          final float f1 = utils.readFloat(stream);
          result.vector[0] = f1;
          // conditions to ensure bounds checks skips
          for (int i = 1; i < batchSize && batchSize <= result.vector.length; i++) {
            final float f2 = utils.readFloat(stream);
            repeating = repeating && (f1 == f2);
            result.vector[i] = f2;
          }
          result.isRepeating = repeating;
        }
      }
    }

    @Override
    protected void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      for (int i = 0; i < items; ++i) {
        utils.readFloat(stream);
      }
    }
  }

  public static class DoubleTreeReader extends TreeReader {
    protected InStream stream;
    private final SerializationUtils utils;

    DoubleTreeReader(int columnId) throws IOException {
      this(columnId, null, null);
    }

    protected DoubleTreeReader(int columnId, InStream present, InStream data) throws IOException {
      super(columnId, present, null);
      this.utils = new SerializationUtils();
      this.stream = data;
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      StreamName name =
          new StreamName(columnId,
              OrcProto.Stream.Kind.DATA);
      stream = planner.getStream(name);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      stream.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final DoubleColumnVector result = (DoubleColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      final boolean hasNulls = !result.noNulls;
      boolean allNulls = hasNulls;
      if (batchSize != 0) {
        if (hasNulls) {
          // conditions to ensure bounds checks skips
          for (int i = 0; i < batchSize && batchSize <= result.isNull.length; i++) {
            allNulls = allNulls & result.isNull[i];
          }
          if (allNulls) {
            result.vector[0] = Double.NaN;
            result.isRepeating = true;
          } else {
            // some nulls
            result.isRepeating = false;
            // conditions to ensure bounds checks skips
            for (int i = 0; batchSize <= result.isNull.length
                && batchSize <= result.vector.length && i < batchSize; i++) {
              if (!result.isNull[i]) {
                result.vector[i] = utils.readDouble(stream);
              } else {
                // If the value is not present then set NaN
                result.vector[i] = Double.NaN;
              }
            }
          }
        } else {
          // no nulls
          boolean repeating = (batchSize > 1);
          final double d1 = utils.readDouble(stream);
          result.vector[0] = d1;
          // conditions to ensure bounds checks skips
          for (int i = 1; i < batchSize && batchSize <= result.vector.length; i++) {
            final double d2 = utils.readDouble(stream);
            repeating = repeating && (d1 == d2);
            result.vector[i] = d2;
          }
          result.isRepeating = repeating;
        }
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long len = items * 8;
      while (len > 0) {
        len -= stream.skip(len);
      }
    }
  }

  public static class BinaryTreeReader extends TreeReader {
    protected InStream stream;
    protected IntegerReader lengths = null;
    protected final LongColumnVector scratchlcv;

    BinaryTreeReader(int columnId, Context context) throws IOException {
      this(columnId, null, null, null, null, context);
    }

    protected BinaryTreeReader(int columnId, InStream present, InStream data, InStream length,
        OrcProto.ColumnEncoding encoding, Context context) throws IOException {
      super(columnId, present, context);
      scratchlcv = new LongColumnVector();
      this.stream = data;
      if (length != null && encoding != null) {
        checkEncoding(encoding);
        this.lengths = createIntegerReader(encoding.getKind(), length, false, context);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = planner.getStream(name);
      lengths = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(new StreamName(columnId, OrcProto.Stream.Kind.LENGTH)), false, context);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      stream.seek(index);
      lengths.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final BytesColumnVector result = (BytesColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      scratchlcv.ensureSize(batchSize, false);
      BytesColumnVectorUtil.readOrcByteArrays(stream, lengths, scratchlcv, result, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long lengthToSkip = 0;
      for (int i = 0; i < items; ++i) {
        lengthToSkip += lengths.next();
      }
      while (lengthToSkip > 0) {
        lengthToSkip -= stream.skip(lengthToSkip);
      }
    }
  }

  public static class TimestampTreeReader extends TreeReader {
    protected IntegerReader data = null;
    protected IntegerReader nanos = null;
    private Map<String, Long> baseTimestampMap;
    protected long base_timestamp;
    private final TimeZone readerTimeZone;
    private final boolean instantType;
    private TimeZone writerTimeZone;
    private boolean hasSameTZRules;
    private ThreadLocal<DateFormat> threadLocalDateFormat;

    TimestampTreeReader(int columnId, Context context,
                        boolean instantType) throws IOException {
      this(columnId, null, null, null, null, context, instantType);
    }

    protected TimestampTreeReader(int columnId, InStream presentStream, InStream dataStream,
                                  InStream nanosStream,
                                  OrcProto.ColumnEncoding encoding,
                                  Context context,
                                  boolean instantType) throws IOException {
      super(columnId, presentStream, context);
      this.instantType = instantType;
      this.threadLocalDateFormat = new ThreadLocal<>();
      this.threadLocalDateFormat.set(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
      this.baseTimestampMap = new HashMap<>();
      if (instantType || context.getUseUTCTimestamp()) {
        this.readerTimeZone = TimeZone.getTimeZone("UTC");
      } else {
        this.readerTimeZone = TimeZone.getDefault();
      }
      if (context.getWriterTimezone() == null || context.getWriterTimezone().isEmpty()) {
        if (instantType) {
          this.base_timestamp = getBaseTimestamp(readerTimeZone.getID()); // UTC
        } else {
          this.base_timestamp = getBaseTimestamp(TimeZone.getDefault().getID());
        }
      } else {
        this.base_timestamp = getBaseTimestamp(context.getWriterTimezone());
      }
      if (encoding != null) {
        checkEncoding(encoding);

        if (dataStream != null) {
          this.data = createIntegerReader(encoding.getKind(), dataStream, true, context);
        }

        if (nanosStream != null) {
          this.nanos = createIntegerReader(encoding.getKind(), nanosStream, false, context);
        }
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      OrcProto.ColumnEncoding.Kind kind = planner.getEncoding(columnId).getKind();
      data = createIntegerReader(kind,
          planner.getStream(new StreamName(columnId,
              OrcProto.Stream.Kind.DATA)), true, context);
      nanos = createIntegerReader(kind,
          planner.getStream(new StreamName(columnId,
              OrcProto.Stream.Kind.SECONDARY)), false, context);
      if (!instantType) {
        base_timestamp = getBaseTimestamp(planner.getWriterTimezone());
      }
    }

    protected long getBaseTimestamp(String timeZoneId) throws IOException {
      // to make sure new readers read old files in the same way
      if (timeZoneId == null || timeZoneId.isEmpty()) {
        timeZoneId = writerTimeZone.getID();
      }

      if (writerTimeZone == null || !timeZoneId.equals(writerTimeZone.getID())) {
        writerTimeZone = TimeZone.getTimeZone(timeZoneId);
        hasSameTZRules = writerTimeZone.hasSameRules(readerTimeZone);
        if (!baseTimestampMap.containsKey(timeZoneId)) {
          threadLocalDateFormat.get().setTimeZone(writerTimeZone);
          try {
            long epoch = threadLocalDateFormat.get()
                             .parse(TimestampTreeWriter.BASE_TIMESTAMP_STRING).getTime() /
                             TimestampTreeWriter.MILLIS_PER_SECOND;
            baseTimestampMap.put(timeZoneId, epoch);
            return epoch;
          } catch (ParseException e) {
            throw new IOException("Unable to create base timestamp", e);
          } finally {
            threadLocalDateFormat.get().setTimeZone(readerTimeZone);
          }
        } else {
          return baseTimestampMap.get(timeZoneId);
        }
      }

      return base_timestamp;
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      data.seek(index);
      nanos.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      TimestampColumnVector result = (TimestampColumnVector) previousVector;
      super.nextVector(previousVector, isNull, batchSize);

      result.setIsUTC(context.getUseUTCTimestamp());

      for (int i = 0; i < batchSize; i++) {
        if (result.noNulls || !result.isNull[i]) {
          final int newNanos = parseNanos(nanos.next());
          long millis = (data.next() + base_timestamp)
              * TimestampTreeWriter.MILLIS_PER_SECOND + newNanos / 1_000_000;
          if (millis < 0 && newNanos > 999_999) {
            millis -= TimestampTreeWriter.MILLIS_PER_SECOND;
          }
          long offset = 0;
          // If reader and writer time zones have different rules, adjust the timezone difference
          // between reader and writer taking day light savings into account.
          if (!hasSameTZRules) {
            offset = SerializationUtils.convertBetweenTimezones(writerTimeZone,
                readerTimeZone, millis);
          }
          result.time[i] = millis + offset;
          result.nanos[i] = newNanos;
          if (result.isRepeating && i != 0 &&
              (result.time[0] != result.time[i] ||
                  result.nanos[0] != result.nanos[i])) {
            result.isRepeating = false;
          }
        }
      }
    }

    private static int parseNanos(long serialized) {
      int zeros = 7 & (int) serialized;
      int result = (int) (serialized >>> 3);
      if (zeros != 0) {
        for (int i = 0; i <= zeros; ++i) {
          result *= 10;
        }
      }
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      data.skip(items);
      nanos.skip(items);
    }
  }

  public static class DateTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    DateTreeReader(int columnId, Context context) throws IOException {
      this(columnId, null, null, null, context);
    }

    protected DateTreeReader(int columnId, InStream present, InStream data,
        OrcProto.ColumnEncoding encoding, Context context) throws IOException {
      super(columnId, present, context);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        reader = createIntegerReader(encoding.getKind(), data, true, context);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(name), true, context);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final LongColumnVector result = (LongColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, result.vector, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class DecimalTreeReader extends TreeReader {
    protected final int precision;
    protected final int scale;
    protected InStream valueStream;
    protected IntegerReader scaleReader = null;
    private int[] scratchScaleVector;
    private byte[] scratchBytes;

    DecimalTreeReader(int columnId,
                      int precision,
                      int scale,
                      Context context) throws IOException {
      this(columnId, null, null, null, null, precision, scale, context);
    }

    protected DecimalTreeReader(int columnId,
                                InStream present,
                                InStream valueStream,
                                InStream scaleStream,
                                OrcProto.ColumnEncoding encoding,
                                int precision,
                                int scale,
                                Context context) throws IOException {
      super(columnId, present, context);
      this.precision = precision;
      this.scale = scale;
      this.scratchScaleVector = new int[VectorizedRowBatch.DEFAULT_SIZE];
      this.valueStream = valueStream;
      this.scratchBytes = new byte[HiveDecimal.SCRATCH_BUFFER_LEN_SERIALIZATION_UTILS_READ];
      if (scaleStream != null && encoding != null) {
        checkEncoding(encoding);
        this.scaleReader = createIntegerReader(encoding.getKind(), scaleStream, true, context);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      valueStream = planner.getStream(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA));
      scaleReader = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(new StreamName(columnId, OrcProto.Stream.Kind.SECONDARY)), true, context);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      valueStream.seek(index);
      scaleReader.seek(index);
    }

    private void nextVector(DecimalColumnVector result,
                            boolean[] isNull,
                            final int batchSize) throws IOException {
      if (batchSize > scratchScaleVector.length) {
        scratchScaleVector = new int[(int) batchSize];
      }
      // read the scales
      scaleReader.nextVector(result, scratchScaleVector, batchSize);
      // Read value entries based on isNull entries
      // Use the fast ORC deserialization method that emulates SerializationUtils.readBigInteger
      // provided by HiveDecimalWritable.
      HiveDecimalWritable[] vector = result.vector;
      HiveDecimalWritable decWritable;
      if (result.noNulls) {
        for (int r=0; r < batchSize; ++r) {
          decWritable = vector[r];
          if (!decWritable.serializationUtilsRead(
              valueStream, scratchScaleVector[r],
              scratchBytes)) {
            result.isNull[r] = true;
            result.noNulls = false;
          }
        }
      } else if (!result.isRepeating || !result.isNull[0]) {
        for (int r=0; r < batchSize; ++r) {
          if (!result.isNull[r]) {
            decWritable = vector[r];
            if (!decWritable.serializationUtilsRead(
                valueStream, scratchScaleVector[r],
                scratchBytes)) {
              result.isNull[r] = true;
              result.noNulls = false;
            }
          }
        }
      }
    }

    private void nextVector(Decimal64ColumnVector result,
                            boolean[] isNull,
                            final int batchSize) throws IOException {
      if (precision > TypeDescription.MAX_DECIMAL64_PRECISION) {
        throw new IllegalArgumentException("Reading large precision type into" +
            " Decimal64ColumnVector.");
      }

      if (batchSize > scratchScaleVector.length) {
        scratchScaleVector = new int[(int) batchSize];
      }
      // read the scales
      scaleReader.nextVector(result, scratchScaleVector, batchSize);
      if (result.noNulls) {
        for (int r=0; r < batchSize; ++r) {
          result.vector[r] = SerializationUtils.readVslong(valueStream);
          for(int s=scratchScaleVector[r]; s < scale; ++s) {
            result.vector[r] *= 10;
          }
        }
      } else if (!result.isRepeating || !result.isNull[0]) {
        for (int r=0; r < batchSize; ++r) {
          if (!result.isNull[r]) {
            result.vector[r] = SerializationUtils.readVslong(valueStream);
            for(int s=scratchScaleVector[r]; s < scale; ++s) {
              result.vector[r] *= 10;
            }
          }
        }
      }
      result.precision = (short) precision;
      result.scale = (short) scale;
    }

    @Override
    public void nextVector(ColumnVector result,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);
      if (result instanceof Decimal64ColumnVector) {
        nextVector((Decimal64ColumnVector) result, isNull, batchSize);
      } else {
        nextVector((DecimalColumnVector) result, isNull, batchSize);
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      HiveDecimalWritable scratchDecWritable = new HiveDecimalWritable();
      for (int i = 0; i < items; i++) {
        scratchDecWritable.serializationUtilsRead(valueStream, 0, scratchBytes);
      }
      scaleReader.skip(items);
    }
  }

  public static class Decimal64TreeReader extends TreeReader {
    protected final int precision;
    protected final int scale;
    protected final boolean skipCorrupt;
    protected RunLengthIntegerReaderV2 valueReader;

    Decimal64TreeReader(int columnId,
                      int precision,
                      int scale,
                      Context context) throws IOException {
      this(columnId, null, null, null, precision, scale, context);
    }

    protected Decimal64TreeReader(int columnId,
                                InStream present,
                                InStream valueStream,
                                OrcProto.ColumnEncoding encoding,
                                int precision,
                                int scale,
                                Context context) throws IOException {
      super(columnId, present, context);
      this.precision = precision;
      this.scale = scale;
      valueReader = new RunLengthIntegerReaderV2(valueStream, true,
          context.isSkipCorrupt());
      skipCorrupt = context.isSkipCorrupt();
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      InStream stream = planner.getStream(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA));
      valueReader = new RunLengthIntegerReaderV2(stream, true, skipCorrupt);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      valueReader.seek(index);
    }

    private void nextVector(DecimalColumnVector result,
                            final int batchSize) throws IOException {
      if (result.noNulls) {
        for (int r=0; r < batchSize; ++r) {
          result.vector[r].setFromLongAndScale(valueReader.next(), scale);
        }
      } else if (!result.isRepeating || !result.isNull[0]) {
        for (int r=0; r < batchSize; ++r) {
          if (result.noNulls || !result.isNull[r]) {
            result.vector[r].setFromLongAndScale(valueReader.next(), scale);
          }
        }
      }
      result.precision = (short) precision;
      result.scale = (short) scale;
    }

    private void nextVector(Decimal64ColumnVector result,
                            final int batchSize) throws IOException {
      valueReader.nextVector(result, result.vector, batchSize);
      result.precision = (short) precision;
      result.scale = (short) scale;
    }

    @Override
    public void nextVector(ColumnVector result,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);
      if (result instanceof Decimal64ColumnVector) {
        nextVector((Decimal64ColumnVector) result, batchSize);
      } else {
        nextVector((DecimalColumnVector) result, batchSize);
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      valueReader.skip(items);
    }
  }

  /**
   * A tree reader that will read string columns. At the start of the
   * stripe, it creates an internal reader based on whether a direct or
   * dictionary encoding was used.
   */
  public static class StringTreeReader extends TreeReader {
    protected TreeReader reader;

    StringTreeReader(int columnId, Context context) throws IOException {
      super(columnId, context);
    }

    protected StringTreeReader(int columnId, InStream present, InStream data, InStream length,
        InStream dictionary, OrcProto.ColumnEncoding encoding, Context context) throws IOException {
      super(columnId, present, context);
      if (encoding != null) {
        switch (encoding.getKind()) {
          case DIRECT:
          case DIRECT_V2:
            reader = new StringDirectTreeReader(columnId, present, data, length,
                encoding.getKind());
            break;
          case DICTIONARY:
          case DICTIONARY_V2:
            reader = new StringDictionaryTreeReader(columnId, present, data, length, dictionary,
                encoding, context);
            break;
          default:
            throw new IllegalArgumentException("Unsupported encoding " +
                encoding.getKind());
        }
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      reader.checkEncoding(encoding);
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      // For each stripe, checks the encoding and initializes the appropriate
      // reader
      switch (planner.getEncoding(columnId).getKind()) {
        case DIRECT:
        case DIRECT_V2:
          reader = new StringDirectTreeReader(columnId);
          break;
        case DICTIONARY:
        case DICTIONARY_V2:
          reader = new StringDictionaryTreeReader(columnId, context);
          break;
        default:
          throw new IllegalArgumentException("Unsupported encoding " +
              planner.getEncoding(columnId).getKind());
      }
      reader.startStripe(planner);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      reader.seek(index);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      reader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      reader.nextVector(previousVector, isNull, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skipRows(items);
    }
  }

  // This class collects together very similar methods for reading an ORC vector of byte arrays and
  // creating the BytesColumnVector.
  //
  public static class BytesColumnVectorUtil {

    private static byte[] commonReadByteArrays(InStream stream, IntegerReader lengths,
        LongColumnVector scratchlcv,
        BytesColumnVector result, final int batchSize) throws IOException {
      // Read lengths
      scratchlcv.isRepeating = result.isRepeating;
      scratchlcv.noNulls = result.noNulls;
      scratchlcv.isNull = result.isNull;  // Notice we are replacing the isNull vector here...
      lengths.nextVector(scratchlcv, scratchlcv.vector, batchSize);
      int totalLength = 0;
      if (!scratchlcv.isRepeating) {
        for (int i = 0; i < batchSize; i++) {
          if (!scratchlcv.isNull[i]) {
            totalLength += (int) scratchlcv.vector[i];
          }
        }
      } else {
        if (!scratchlcv.isNull[0]) {
          totalLength = (int) (batchSize * scratchlcv.vector[0]);
        }
      }

      // Read all the strings for this batch
      byte[] allBytes = new byte[totalLength];
      int offset = 0;
      int len = totalLength;
      while (len > 0) {
        int bytesRead = stream.read(allBytes, offset, len);
        if (bytesRead < 0) {
          throw new EOFException("Can't finish byte read from " + stream);
        }
        len -= bytesRead;
        offset += bytesRead;
      }

      return allBytes;
    }

    // This method has the common code for reading in bytes into a BytesColumnVector.
    public static void readOrcByteArrays(InStream stream,
                                         IntegerReader lengths,
                                         LongColumnVector scratchlcv,
                                         BytesColumnVector result,
                                         final int batchSize) throws IOException {
      if (result.noNulls || !(result.isRepeating && result.isNull[0])) {
        byte[] allBytes = commonReadByteArrays(stream, lengths, scratchlcv,
            result, (int) batchSize);

        // Too expensive to figure out 'repeating' by comparisons.
        result.isRepeating = false;
        int offset = 0;
        if (!scratchlcv.isRepeating) {
          for (int i = 0; i < batchSize; i++) {
            if (!scratchlcv.isNull[i]) {
              result.setRef(i, allBytes, offset, (int) scratchlcv.vector[i]);
              offset += scratchlcv.vector[i];
            } else {
              result.setRef(i, allBytes, 0, 0);
            }
          }
        } else {
          for (int i = 0; i < batchSize; i++) {
            if (!scratchlcv.isNull[i]) {
              result.setRef(i, allBytes, offset, (int) scratchlcv.vector[0]);
              offset += scratchlcv.vector[0];
            } else {
              result.setRef(i, allBytes, 0, 0);
            }
          }
        }
      }
    }
  }

  /**
   * A reader for string columns that are direct encoded in the current
   * stripe.
   */
  public static class StringDirectTreeReader extends TreeReader {
    private static final HadoopShims SHIMS = HadoopShimsFactory.get();
    protected InStream stream;
    protected IntegerReader lengths;
    private final LongColumnVector scratchlcv;

    StringDirectTreeReader(int columnId) throws IOException {
      this(columnId, null, null, null, null);
    }

    protected StringDirectTreeReader(int columnId, InStream present, InStream data,
        InStream length, OrcProto.ColumnEncoding.Kind encoding) throws IOException {
      super(columnId, present, null);
      this.scratchlcv = new LongColumnVector();
      this.stream = data;
      if (length != null && encoding != null) {
        this.lengths = createIntegerReader(encoding, length, false, context);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT &&
          encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = planner.getStream(name);
      lengths = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(new StreamName(columnId, OrcProto.Stream.Kind.LENGTH)),
          false, context);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      stream.seek(index);
      // don't seek data stream
      lengths.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final BytesColumnVector result = (BytesColumnVector) previousVector;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      scratchlcv.ensureSize(batchSize, false);
      BytesColumnVectorUtil.readOrcByteArrays(stream, lengths, scratchlcv,
          result, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long lengthToSkip = 0;
      for (int i = 0; i < items; ++i) {
        lengthToSkip += lengths.next();
      }

      while (lengthToSkip > 0) {
        lengthToSkip -= stream.skip(lengthToSkip);
      }
    }

    public IntegerReader getLengths() {
      return lengths;
    }

    public InStream getStream() {
      return stream;
    }
  }

  /**
   * A reader for string columns that are dictionary encoded in the current
   * stripe.
   */
  public static class StringDictionaryTreeReader extends TreeReader {
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private DynamicByteArray dictionaryBuffer;
    private int[] dictionaryOffsets;
    protected IntegerReader reader;

    private byte[] dictionaryBufferInBytesCache = null;
    private final LongColumnVector scratchlcv;

    StringDictionaryTreeReader(int columnId, Context context) throws IOException {
      this(columnId, null, null, null, null, null, context);
    }

    protected StringDictionaryTreeReader(int columnId, InStream present, InStream data,
        InStream length, InStream dictionary, OrcProto.ColumnEncoding encoding,
        Context context) throws IOException {
      super(columnId, present, context);
      scratchlcv = new LongColumnVector();
      if (data != null && encoding != null) {
        this.reader = createIntegerReader(encoding.getKind(), data, false, context);
      }

      if (dictionary != null && encoding != null) {
        readDictionaryStream(dictionary);
      }

      if (length != null && encoding != null) {
        readDictionaryLengthStream(length, encoding);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DICTIONARY &&
          encoding.getKind() != OrcProto.ColumnEncoding.Kind.DICTIONARY_V2) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);

      // read the dictionary blob
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DICTIONARY_DATA);
      InStream in = planner.getStream(name);
      readDictionaryStream(in);

      // read the lengths
      name = new StreamName(columnId, OrcProto.Stream.Kind.LENGTH);
      in = planner.getStream(name);
      OrcProto.ColumnEncoding encoding = planner.getEncoding(columnId);
      readDictionaryLengthStream(in, encoding);

      // set up the row reader
      name = new StreamName(columnId, OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(encoding.getKind(),
          planner.getStream(name), false, context);
    }

    private void readDictionaryLengthStream(InStream in, OrcProto.ColumnEncoding encoding)
        throws IOException {
      int dictionarySize = encoding.getDictionarySize();
      if (in != null) { // Guard against empty LENGTH stream.
        IntegerReader lenReader = createIntegerReader(encoding.getKind(), in, false, context);
        int offset = 0;
        if (dictionaryOffsets == null ||
            dictionaryOffsets.length < dictionarySize + 1) {
          dictionaryOffsets = new int[dictionarySize + 1];
        }
        for (int i = 0; i < dictionarySize; ++i) {
          dictionaryOffsets[i] = offset;
          offset += (int) lenReader.next();
        }
        dictionaryOffsets[dictionarySize] = offset;
        in.close();
      }

    }

    private void readDictionaryStream(InStream in) throws IOException {
      if (in != null) { // Guard against empty dictionary stream.
        if (in.available() > 0) {
          dictionaryBuffer = new DynamicByteArray(64, in.available());
          dictionaryBuffer.readAll(in);
          // Since its start of strip invalidate the cache.
          dictionaryBufferInBytesCache = null;
        }
        in.close();
      } else {
        dictionaryBuffer = null;
      }
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      final BytesColumnVector result = (BytesColumnVector) previousVector;
      int offset;
      int length;

      // Read present/isNull stream
      super.nextVector(result, isNull, batchSize);

      if (dictionaryBuffer != null) {

        // Load dictionaryBuffer into cache.
        if (dictionaryBufferInBytesCache == null) {
          dictionaryBufferInBytesCache = dictionaryBuffer.get();
        }

        // Read string offsets
        scratchlcv.isRepeating = result.isRepeating;
        scratchlcv.noNulls = result.noNulls;
        scratchlcv.isNull = result.isNull;
        scratchlcv.ensureSize((int) batchSize, false);
        reader.nextVector(scratchlcv, scratchlcv.vector, batchSize);
        if (!scratchlcv.isRepeating) {

          // The vector has non-repeating strings. Iterate thru the batch
          // and set strings one by one
          for (int i = 0; i < batchSize; i++) {
            if (!scratchlcv.isNull[i]) {
              offset = dictionaryOffsets[(int) scratchlcv.vector[i]];
              length = getDictionaryEntryLength((int) scratchlcv.vector[i], offset);
              result.setRef(i, dictionaryBufferInBytesCache, offset, length);
            } else {
              // If the value is null then set offset and length to zero (null string)
              result.setRef(i, dictionaryBufferInBytesCache, 0, 0);
            }
          }
        } else {
          // If the value is repeating then just set the first value in the
          // vector and set the isRepeating flag to true. No need to iterate thru and
          // set all the elements to the same value
          offset = dictionaryOffsets[(int) scratchlcv.vector[0]];
          length = getDictionaryEntryLength((int) scratchlcv.vector[0], offset);
          result.setRef(0, dictionaryBufferInBytesCache, offset, length);
        }
        result.isRepeating = scratchlcv.isRepeating;
      } else {
        if (dictionaryOffsets == null) {
          // Entire stripe contains null strings.
          result.isRepeating = true;
          result.noNulls = false;
          result.isNull[0] = true;
          result.setRef(0, EMPTY_BYTE_ARRAY, 0, 0);
        } else {
          // stripe contains nulls and empty strings
          for (int i = 0; i < batchSize; i++) {
            if (!result.isNull[i]) {
              result.setRef(i, EMPTY_BYTE_ARRAY, 0, 0);
            }
          }
        }
      }
    }

    int getDictionaryEntryLength(int entry, int offset) {
      final int length;
      // if it isn't the last entry, subtract the offsets otherwise use
      // the buffer length.
      if (entry < dictionaryOffsets.length - 1) {
        length = dictionaryOffsets[entry + 1] - offset;
      } else {
        length = dictionaryBuffer.size() - offset;
      }
      return length;
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }

    public IntegerReader getReader() {
      return reader;
    }
  }

  public static class CharTreeReader extends StringTreeReader {
    int maxLength;

    CharTreeReader(int columnId, int maxLength) throws IOException {
      this(columnId, maxLength, null, null, null, null, null);
    }

    protected CharTreeReader(int columnId, int maxLength, InStream present, InStream data,
        InStream length, InStream dictionary, OrcProto.ColumnEncoding encoding) throws IOException {
      super(columnId, present, data, length, dictionary, encoding, null);
      this.maxLength = maxLength;
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      // Get the vector of strings from StringTreeReader, then make a 2nd pass to
      // adjust down the length (right trim and truncate) if necessary.
      super.nextVector(previousVector, isNull, batchSize);
      BytesColumnVector result = (BytesColumnVector) previousVector;
      int adjustedDownLen;
      if (result.isRepeating) {
        if (result.noNulls || !result.isNull[0]) {
          adjustedDownLen = StringExpr
              .rightTrimAndTruncate(result.vector[0], result.start[0], result.length[0], maxLength);
          if (adjustedDownLen < result.length[0]) {
            result.setRef(0, result.vector[0], result.start[0], adjustedDownLen);
          }
        }
      } else {
        if (result.noNulls) {
          for (int i = 0; i < batchSize; i++) {
            adjustedDownLen = StringExpr
                .rightTrimAndTruncate(result.vector[i], result.start[i], result.length[i],
                    maxLength);
            if (adjustedDownLen < result.length[i]) {
              result.setRef(i, result.vector[i], result.start[i], adjustedDownLen);
            }
          }
        } else {
          for (int i = 0; i < batchSize; i++) {
            if (!result.isNull[i]) {
              adjustedDownLen = StringExpr
                  .rightTrimAndTruncate(result.vector[i], result.start[i], result.length[i],
                      maxLength);
              if (adjustedDownLen < result.length[i]) {
                result.setRef(i, result.vector[i], result.start[i], adjustedDownLen);
              }
            }
          }
        }
      }
    }
  }

  public static class VarcharTreeReader extends StringTreeReader {
    int maxLength;

    VarcharTreeReader(int columnId, int maxLength) throws IOException {
      this(columnId, maxLength, null, null, null, null, null);
    }

    protected VarcharTreeReader(int columnId, int maxLength, InStream present, InStream data,
        InStream length, InStream dictionary, OrcProto.ColumnEncoding encoding) throws IOException {
      super(columnId, present, data, length, dictionary, encoding, null);
      this.maxLength = maxLength;
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      // Get the vector of strings from StringTreeReader, then make a 2nd pass to
      // adjust down the length (truncate) if necessary.
      super.nextVector(previousVector, isNull, batchSize);
      BytesColumnVector result = (BytesColumnVector) previousVector;

      int adjustedDownLen;
      if (result.isRepeating) {
        if (result.noNulls || !result.isNull[0]) {
          adjustedDownLen = StringExpr
              .truncate(result.vector[0], result.start[0], result.length[0], maxLength);
          if (adjustedDownLen < result.length[0]) {
            result.setRef(0, result.vector[0], result.start[0], adjustedDownLen);
          }
        }
      } else {
        if (result.noNulls) {
          for (int i = 0; i < batchSize; i++) {
            adjustedDownLen = StringExpr
                .truncate(result.vector[i], result.start[i], result.length[i], maxLength);
            if (adjustedDownLen < result.length[i]) {
              result.setRef(i, result.vector[i], result.start[i], adjustedDownLen);
            }
          }
        } else {
          for (int i = 0; i < batchSize; i++) {
            if (!result.isNull[i]) {
              adjustedDownLen = StringExpr
                  .truncate(result.vector[i], result.start[i], result.length[i], maxLength);
              if (adjustedDownLen < result.length[i]) {
                result.setRef(i, result.vector[i], result.start[i], adjustedDownLen);
              }
            }
          }
        }
      }
    }
  }

  public static class StructTreeReader extends TreeReader {
    protected final TreeReader[] fields;

    protected StructTreeReader(int columnId,
                               TypeDescription readerSchema,
                               Context context) throws IOException {
      super(columnId, context);

      List<TypeDescription> childrenTypes = readerSchema.getChildren();
      this.fields = new TreeReader[childrenTypes.size()];
      for (int i = 0; i < fields.length; ++i) {
        TypeDescription subtype = childrenTypes.get(i);
        this.fields[i] = createTreeReader(subtype, context);
      }
    }

    public TreeReader[] getChildReaders() {
      return fields;
    }

    protected StructTreeReader(int columnId, InStream present,
                               Context context,
                               OrcProto.ColumnEncoding encoding,
                               TreeReader[] childReaders) throws IOException {
      super(columnId, present, context);
      if (encoding != null) {
        checkEncoding(encoding);
      }
      this.fields = childReaders;
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      for (TreeReader kid : fields) {
        if (kid != null) {
          kid.seek(index);
        }
      }
    }

    @Override
    public void nextBatch(VectorizedRowBatch batch,
                          int batchSize) throws IOException {
      for(int i=0; i < fields.length &&
          (vectorColumnCount == -1 || i < vectorColumnCount); ++i) {
        ColumnVector colVector = batch.cols[i];
        if (colVector != null) {
          colVector.reset();
          colVector.ensureSize((int) batchSize, false);
          fields[i].nextVector(colVector, null, batchSize);
        }
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      super.nextVector(previousVector, isNull, batchSize);
      StructColumnVector result = (StructColumnVector) previousVector;
      if (result.noNulls || !(result.isRepeating && result.isNull[0])) {
        result.isRepeating = false;

        // Read all the members of struct as column vectors
        boolean[] mask = result.noNulls ? null : result.isNull;
        for (int f = 0; f < fields.length; f++) {
          if (fields[f] != null) {
            fields[f].nextVector(result.fields[f], mask, batchSize);
          }
        }
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      for (TreeReader field : fields) {
        if (field != null) {
          field.startStripe(planner);
        }
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      for (TreeReader field : fields) {
        if (field != null) {
          field.skipRows(items);
        }
      }
    }
  }

  public static class UnionTreeReader extends TreeReader {
    protected final TreeReader[] fields;
    protected RunLengthByteReader tags;

    protected UnionTreeReader(int fileColumn,
                              TypeDescription readerSchema,
                              Context context) throws IOException {
      super(fileColumn, context);
      List<TypeDescription> childrenTypes = readerSchema.getChildren();
      int fieldCount = childrenTypes.size();
      this.fields = new TreeReader[fieldCount];
      for (int i = 0; i < fieldCount; ++i) {
        TypeDescription subtype = childrenTypes.get(i);
        this.fields[i] = createTreeReader(subtype, context);
      }
    }

    protected UnionTreeReader(int columnId, InStream present,
                              Context context,
                              OrcProto.ColumnEncoding encoding,
                              TreeReader[] childReaders) throws IOException {
      super(columnId, present, context);
      if (encoding != null) {
        checkEncoding(encoding);
      }
      this.fields = childReaders;
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      tags.seek(index[columnId]);
      for (TreeReader kid : fields) {
        kid.seek(index);
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      UnionColumnVector result = (UnionColumnVector) previousVector;
      super.nextVector(result, isNull, batchSize);
      if (result.noNulls || !(result.isRepeating && result.isNull[0])) {
        result.isRepeating = false;
        tags.nextVector(result.noNulls ? null : result.isNull, result.tags,
            batchSize);
        boolean[] ignore = new boolean[(int) batchSize];
        for (int f = 0; f < result.fields.length; ++f) {
          // build the ignore list for this tag
          for (int r = 0; r < batchSize; ++r) {
            ignore[r] = (!result.noNulls && result.isNull[r]) ||
                result.tags[r] != f;
          }
          fields[f].nextVector(result.fields[f], ignore, batchSize);
        }
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      tags = new RunLengthByteReader(planner.getStream(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA)));
      for (TreeReader field : fields) {
        if (field != null) {
          field.startStripe(planner);
        }
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long[] counts = new long[fields.length];
      for (int i = 0; i < items; ++i) {
        counts[tags.next()] += 1;
      }
      for (int i = 0; i < counts.length; ++i) {
        fields[i].skipRows(counts[i]);
      }
    }
  }

  public static class ListTreeReader extends TreeReader {
    protected final TreeReader elementReader;
    protected IntegerReader lengths = null;

    protected ListTreeReader(int fileColumn,
                             TypeDescription readerSchema,
                             Context context) throws IOException {
      super(fileColumn, context);
      TypeDescription elementType = readerSchema.getChildren().get(0);
      elementReader = createTreeReader(elementType, context);
    }

    protected ListTreeReader(int columnId,
                             InStream present,
                             Context context,
                             InStream data,
                             OrcProto.ColumnEncoding encoding,
                             TreeReader elementReader) throws IOException {
      super(columnId, present, context);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.lengths = createIntegerReader(encoding.getKind(), data, false, context);
      }
      this.elementReader = elementReader;
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      lengths.seek(index[columnId]);
      elementReader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previous,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      ListColumnVector result = (ListColumnVector) previous;
      super.nextVector(result, isNull, batchSize);
      // if we have some none-null values, then read them
      if (result.noNulls || !(result.isRepeating && result.isNull[0])) {
        lengths.nextVector(result, result.lengths, batchSize);
        // even with repeating lengths, the list doesn't repeat
        result.isRepeating = false;
        // build the offsets vector and figure out how many children to read
        result.childCount = 0;
        for (int r = 0; r < batchSize; ++r) {
          if (result.noNulls || !result.isNull[r]) {
            result.offsets[r] = result.childCount;
            result.childCount += result.lengths[r];
          }
        }
        result.child.ensureSize(result.childCount, false);
        elementReader.nextVector(result.child, null, result.childCount);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      lengths = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(new StreamName(columnId,
              OrcProto.Stream.Kind.LENGTH)), false, context);
      if (elementReader != null) {
        elementReader.startStripe(planner);
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long childSkip = 0;
      for (long i = 0; i < items; ++i) {
        childSkip += lengths.next();
      }
      elementReader.skipRows(childSkip);
    }
  }

  public static class MapTreeReader extends TreeReader {
    protected final TreeReader keyReader;
    protected final TreeReader valueReader;
    protected IntegerReader lengths = null;

    protected MapTreeReader(int fileColumn,
                            TypeDescription readerSchema,
                            Context context) throws IOException {
      super(fileColumn, context);
      TypeDescription keyType = readerSchema.getChildren().get(0);
      TypeDescription valueType = readerSchema.getChildren().get(1);
      keyReader = createTreeReader(keyType, context);
      valueReader = createTreeReader(valueType, context);
    }

    protected MapTreeReader(int columnId,
                            InStream present,
                            Context context,
                            InStream data,
                            OrcProto.ColumnEncoding encoding,
                            TreeReader keyReader,
                            TreeReader valueReader) throws IOException {
      super(columnId, present, context);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.lengths = createIntegerReader(encoding.getKind(), data, false, context);
      }
      this.keyReader = keyReader;
      this.valueReader = valueReader;
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      lengths.seek(index[columnId]);
      keyReader.seek(index);
      valueReader.seek(index);
    }

    @Override
    public void nextVector(ColumnVector previous,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      MapColumnVector result = (MapColumnVector) previous;
      super.nextVector(result, isNull, batchSize);
      if (result.noNulls || !(result.isRepeating && result.isNull[0])) {
        lengths.nextVector(result, result.lengths, batchSize);
        // even with repeating lengths, the map doesn't repeat
        result.isRepeating = false;
        // build the offsets vector and figure out how many children to read
        result.childCount = 0;
        for (int r = 0; r < batchSize; ++r) {
          if (result.noNulls || !result.isNull[r]) {
            result.offsets[r] = result.childCount;
            result.childCount += result.lengths[r];
          }
        }
        result.keys.ensureSize(result.childCount, false);
        result.values.ensureSize(result.childCount, false);
        keyReader.nextVector(result.keys, null, result.childCount);
        valueReader.nextVector(result.values, null, result.childCount);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(StripePlanner planner) throws IOException {
      super.startStripe(planner);
      lengths = createIntegerReader(planner.getEncoding(columnId).getKind(),
          planner.getStream(new StreamName(columnId,
              OrcProto.Stream.Kind.LENGTH)), false, context);
      if (keyReader != null) {
        keyReader.startStripe(planner);
      }
      if (valueReader != null) {
        valueReader.startStripe(planner);
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long childSkip = 0;
      for (long i = 0; i < items; ++i) {
        childSkip += lengths.next();
      }
      keyReader.skipRows(childSkip);
      valueReader.skipRows(childSkip);
    }
  }

  public static TreeReader createTreeReader(TypeDescription readerType,
                                            Context context
                                            ) throws IOException {
    OrcFile.Version version = context.getFileFormat();
    final SchemaEvolution evolution = context.getSchemaEvolution();
    TypeDescription fileType = evolution.getFileType(readerType);
    if (fileType == null || !evolution.includeReaderColumn(readerType.getId())){
      return new NullTreeReader(0);
    }
    TypeDescription.Category readerTypeCategory = readerType.getCategory();
    if (!fileType.equals(readerType) &&
        (readerTypeCategory != TypeDescription.Category.STRUCT &&
         readerTypeCategory != TypeDescription.Category.MAP &&
         readerTypeCategory != TypeDescription.Category.LIST &&
         readerTypeCategory != TypeDescription.Category.UNION)) {
      // We only convert complex children.
      return ConvertTreeReaderFactory.createConvertTreeReader(readerType, context);
    }
    switch (readerTypeCategory) {
      case BOOLEAN:
        return new BooleanTreeReader(fileType.getId());
      case BYTE:
        return new ByteTreeReader(fileType.getId());
      case DOUBLE:
        return new DoubleTreeReader(fileType.getId());
      case FLOAT:
        return new FloatTreeReader(fileType.getId());
      case SHORT:
        return new ShortTreeReader(fileType.getId(), context);
      case INT:
        return new IntTreeReader(fileType.getId(), context);
      case LONG:
        return new LongTreeReader(fileType.getId(), context);
      case STRING:
        return new StringTreeReader(fileType.getId(), context);
      case CHAR:
        return new CharTreeReader(fileType.getId(), readerType.getMaxLength());
      case VARCHAR:
        return new VarcharTreeReader(fileType.getId(), readerType.getMaxLength());
      case BINARY:
        return new BinaryTreeReader(fileType.getId(), context);
      case TIMESTAMP:
        return new TimestampTreeReader(fileType.getId(), context, false);
      case TIMESTAMP_INSTANT:
        return new TimestampTreeReader(fileType.getId(), context, true);
      case DATE:
        return new DateTreeReader(fileType.getId(), context);
      case DECIMAL:
        if (version == OrcFile.Version.UNSTABLE_PRE_2_0 &&
            fileType.getPrecision() <= TypeDescription.MAX_DECIMAL64_PRECISION){
          return new Decimal64TreeReader(fileType.getId(), fileType.getPrecision(),
              fileType.getScale(), context);
        }
        return new DecimalTreeReader(fileType.getId(), fileType.getPrecision(),
            fileType.getScale(), context);
      case STRUCT:
        return new StructTreeReader(fileType.getId(), readerType, context);
      case LIST:
        return new ListTreeReader(fileType.getId(), readerType, context);
      case MAP:
        return new MapTreeReader(fileType.getId(), readerType, context);
      case UNION:
        return new UnionTreeReader(fileType.getId(), readerType, context);
      default:
        throw new IllegalArgumentException("Unsupported type " +
            readerTypeCategory);
    }
  }
}
