package org.broadinstitute.hellbender.engine.spark.datasources;

import htsjdk.samtools.SAMException;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.parquet.avro.AvroParquetInputFormat;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.bdgenomics.formats.avro.AlignmentRecord;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.IntervalUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.read.BDGAlignmentRecordToGATKReadAdapter;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.SAMRecordToGATKReadAdapter;
import org.seqdoop.hadoop_bam.AnySAMInputFormat;
import org.seqdoop.hadoop_bam.SAMRecordWritable;
import org.seqdoop.hadoop_bam.util.SAMHeaderReader;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/** Loads the reads from disk either serially (using samReaderFactory) or in parallel using Hadoop-BAM.
 * The parallel code is a modified version of the example writing code from Hadoop-BAM.
 */
public class ReadsSparkSource implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final String HADOOP_PART_PREFIX = "part-";

    private transient final JavaSparkContext ctx;
    public ReadsSparkSource(JavaSparkContext ctx) {
        this.ctx = ctx;
    }


    /**
     * Loads Reads using Hadoop-BAM. For local files, bam must have the fully-qualified path,
     * i.e., file:///path/to/bam.bam.
     * @param bam file to load
     * @param intervals intervals of reads to include.
     * @return RDD of (SAMRecord-backed) GATKReads from the file.
     */
    public JavaRDD<GATKRead> getParallelReads(final String bam, final List<SimpleInterval> intervals) {
        Configuration conf = new Configuration();
        // reads take more space in memory than on disk so we need to limit the split size
        // TODO: make this configurable, or tune automatically
       conf.set("mapred.max.split.size", "4194304");

        JavaPairRDD<LongWritable, SAMRecordWritable> rdd2 = ctx.newAPIHadoopFile(
                bam, AnySAMInputFormat.class, LongWritable.class, SAMRecordWritable.class,
                conf);

        return rdd2.map(v1 -> {
            SAMRecord sam = v1._2().get();
            if (samRecordOverlaps(sam, intervals)) {
                try {
                    return SAMRecordToGATKReadAdapter.sparkReadAdapter(sam);
                } catch (SAMException e) {
                    // TODO: add stringency
                }            
            }
            return null;

        }).filter(v1 -> v1 != null);
    }

    /**
     * Loads Reads using Hadoop-BAM. For local files, bam must have the fully-qualified path,
     * i.e., file:///path/to/bam.bam. This excludes unmapped reads.
     * @param bam file to load
     * @return RDD of (SAMRecord-backed) GATKReads from the file.
     */
    public JavaRDD<GATKRead> getParallelReads(final String bam) {
        final SAMFileHeader readsHeader = getHeader(ctx, bam);
        List<SimpleInterval> intervals = IntervalUtils.getAllIntervalsForReference(readsHeader.getSequenceDictionary());
        return getParallelReads(bam, intervals);
    }

    /**
     * Loads ADAM reads stored as Parquet.
     * @param inputPath path to the Parquet data
     * @return RDD of (ADAM-backed) GATKReads from the file.
     */
    public JavaRDD<GATKRead> getADAMReads(final String inputPath, SAMFileHeader header) throws IOException {
        Job job = Job.getInstance(ctx.hadoopConfiguration());
        AvroParquetInputFormat.setAvroReadSchema(job, AlignmentRecord.getClassSchema());
        Broadcast<SAMFileHeader> bHeader;
        if (header == null) {
            bHeader= ctx.broadcast(null);
        } else {
            bHeader = ctx.broadcast(header);
        }
        @SuppressWarnings("unchecked")
        JavaRDD<AlignmentRecord> recordsRdd = ctx.newAPIHadoopFile(
                inputPath, AvroParquetInputFormat.class, Void.class, AlignmentRecord.class, job.getConfiguration())
                .values();
        JavaRDD<GATKRead> readsRdd = recordsRdd.map(record -> new BDGAlignmentRecordToGATKReadAdapter(record, bHeader.getValue()));
        return readsRdd;
    }

    /**
     * Loads the header using Hadoop-BAM.
     * @param filePath path to the bam.
     * @return the header for the bam.
     */
    public static SAMFileHeader getHeader(final JavaSparkContext ctx, final String filePath) {
        try {
            FileSystem fs = FileSystem.get(ctx.hadoopConfiguration());
            Path path = fs.makeQualified(new Path(filePath));
            if (fs.isDirectory(path)) {
                FileStatus[] bamFiles = fs.listStatus(path, new PathFilter() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public boolean accept(Path path) {
                        return path.getName().startsWith(HADOOP_PART_PREFIX);
                    }
                });
                if (bamFiles.length == 0) {
                    throw new UserException("No BAM files to load header from in: " + path);
                }
                path = bamFiles[0].getPath(); // Hadoop-BAM writes the same header to each shard, so use the first one
            }
            return SAMHeaderReader.readSAMHeaderFrom(path, ctx.hadoopConfiguration());
        } catch (IOException e) {
            throw new UserException("unable to loader header: " + e);
        }        /*
        final SAMFileHeader samFileHeader;
        Path path = new Path(filePath);
        if (filePath.endsWith("/")) {
            path = new Path(filePath + "part-r-00000");
        }
        try {
             samFileHeader = SAMHeaderReader.readSAMHeaderFrom(path, ctx.hadoopConfiguration());
        } catch (IOException e) {
            throw new GATKException("unable to loader header: " + e);
        }
        return samFileHeader;*/
    }

    /**
     * Tests if a given SAMRecord overlaps any interval in a collection.
     */
    //TODO: remove this method when https://github.com/broadinstitute/hellbender/issues/559 is fixed
    private static boolean samRecordOverlaps(final SAMRecord record, final List<SimpleInterval> intervals ) {
        if (intervals == null || intervals.isEmpty()) {
            return true;
        }
        for (SimpleInterval interval : intervals) {
            if (record.getReadUnmappedFlag() && record.getAlignmentStart() != SAMRecord.NO_ALIGNMENT_START) {
                // This follows the behavior of htsjdk's SamReader which states that "an unmapped read will be returned
                // by this call if it has a coordinate for the purpose of sorting that is in the query region".
                int start = record.getAlignmentStart();
                return interval.getStart() <= start && interval.getEnd() >= start;
            } else  if (interval.overlaps(record)) {
                return true;
            }
        }
        return false;
    }
}
