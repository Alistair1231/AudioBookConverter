package uk.yermak.audiobookconverter;

import net.bramp.ffmpeg.progress.ProgressParser;
import net.bramp.ffmpeg.progress.TcpProgressParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandles;
import java.net.URISyntaxException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * Created by Yermak on 29-Dec-17.
 */
public class FFMpegNativeConverter implements Callable<String> {
    final static Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final ConversionJob conversionJob;
    private final MediaInfo mediaInfo;
    private final String outputFileName;
    private final ProgressCallback callback;
    private Process process;
    private ProgressParser progressParser = null;


    public FFMpegNativeConverter(ConversionJob conversionJob, MediaInfo mediaInfo, String outputFileName, ProgressCallback callback) {
        this.conversionJob = conversionJob;
        this.mediaInfo = mediaInfo;
        this.outputFileName = outputFileName;
        this.callback = callback;
    }

    @Override
    public String call() throws Exception {
        try {
            if (conversionJob.getStatus().isOver()) return null;
            while (ProgressStatus.PAUSED.equals(conversionJob.getStatus())) Thread.sleep(1000);

            progressParser = new TcpProgressParser(progress -> {
                callback.converted(progress.out_time_ns / 1000000, progress.total_size);
//                logger.debug("Completed conversion: {} from {}", progress.out_time_ns / 1000000, progress.total_size);
                if (progress.isEnd()) {
                    callback.converted(progress.out_time_ns / 1000000, progress.total_size);
                    callback.completedConversion();
                }
            });
            progressParser.start();

            ProcessBuilder ffmpegProcessBuilder;
            OutputParameters outputParameters = conversionJob.getConversionGroup().getOutputParameters();

            if (outputParameters.needReencode(mediaInfo.getCodec())) {
                logger.debug("Re-encoding to {} for {}", outputParameters.format, outputFileName);
                ffmpegProcessBuilder = new ProcessBuilder(outputParameters.format.getReencodingOptions(mediaInfo, progressParser.getUri().toString(), outputFileName, outputParameters));
            } else {
                logger.debug("Transcoding {} stream for {}", outputParameters.format, outputFileName);
                ffmpegProcessBuilder = new ProcessBuilder(outputParameters.format.getTranscodingOptions(mediaInfo, progressParser.getUri().toString(), outputFileName));
            }
            process = ffmpegProcessBuilder.start();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StreamCopier.copy(process.getInputStream(), out);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            StreamCopier.copy(process.getErrorStream(), err);

            boolean finished = false;
            while (!conversionJob.getStatus().isOver() && !finished) {
                finished = process.waitFor(500, TimeUnit.MILLISECONDS);
            }
            logger.debug("ffmpeg out: {}", out);
            logger.warn("ffmpeg err: {}", err);


            if (process.exitValue() != 0) {
                logger.error("Converstion failed: " + err);
                throw new RuntimeException("Converstion failed: "+ err);
            } else {
                DurationVerifier.ffMpegUpdateDuration(mediaInfo, outputFileName);
            }
            return outputFileName;
        } catch (CancellationException ce) {
            return null;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } finally {
            Utils.closeSilently(process);
            Utils.closeSilently(progressParser);
        }
    }


}
