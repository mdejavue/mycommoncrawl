package de.s9mtmeis.jobs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.any23.Any23;
import org.apache.any23.extractor.ExtractorGroup;
import org.apache.any23.extractor.ExtractorRegistryImpl;
import org.apache.any23.extractor.html.HProductExtractorFactory;
import org.apache.any23.filter.IgnoreAccidentalRDFa;
import org.apache.any23.source.DocumentSource;
import org.apache.any23.source.StringDocumentSource;
import org.apache.any23.vocab.SINDICE;
import org.apache.any23.vocab.XHTML;
import org.apache.any23.writer.NTriplesWriter;
import org.apache.any23.writer.ReportingTripleHandler;
import org.apache.any23.writer.TripleHandler;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.log4j.Logger;

import com.martinkl.warc.WARCWritable;


public class HadoopMapper {


	private static final Logger LOG = Logger.getLogger(HadoopJob.class); //?? right?
	protected static enum MAPPERCOUNTER {
		RECORDS_IN,
		EXCEPTIONS
	}

	public static class Extractor extends Mapper<LongWritable, WARCWritable, Text, Text> {
		private Text outKey = new Text();
		private Text outVal = new Text();
		private ArrayList<Pattern> patterns;
		public final static List<String> EXTRACTORS = 
				Arrays.asList(		"html-rdfa",
									"html-microdata",
									"html-mf-hproduct" );
		private Any23 runner;

		public Extractor() {
			// found RDFa pattern
			ExtractorRegistryImpl.getInstance().register(new HProductExtractorFactory());
			LOG.info("Custom Extractor (HProduct) has been registered");
			ExtractorGroup extractorGroup = ExtractorRegistryImpl.getInstance().getExtractorGroup(EXTRACTORS);
			runner = new Any23(extractorGroup);
			LOG.info("Any23 runner has been initialized with extractorGroup");
		}
		
		
		@Override
		public void map(LongWritable key, WARCWritable value, Context context) throws IOException {
			
			if (patterns == null) { // do this only once :)
				patterns = new ArrayList<Pattern>();
				for (String m : context.getConfiguration().get("matchers").split(" ;; "))
				{
					LOG.info("compiling matcher pattern: " + m);					
					patterns.add(Pattern.compile(m));
				}
			}
			
			try {
				if ( value.getRecord().getHeader().getContentType().equals("application/http; msgtype=response")) {
					//Get the text content as a string.
					byte[] rawData = value.getRecord().getContent();
					String pageText = new String(rawData);
					boolean foundSth = false;

					// Increment for LOG
					context.getCounter(MAPPERCOUNTER.RECORDS_IN).increment(1);
					
					
					// Check if patterns occur in document
					for (Pattern p : patterns)
					{
						Matcher m = p.matcher(pageText);
						if (m.find()) {
							foundSth = true;
							break;
						}
					}

					if (foundSth) {    

						/*4*/ DocumentSource source = new StringDocumentSource(pageText, value.getRecord().getHeader().getTargetURI(), value.getRecord().getHeader().getContentType());
						/*5*/ ByteArrayOutputStream out = new ByteArrayOutputStream();
						/*6*/ TripleHandler handler = new ReportingTripleHandler(new IgnoreAccidentalRDFa(new NTriplesWriter(out), true));
						try {
						/*7*/ runner.extract(source, handler);
						} finally {
						/*8*/ handler.close();
						}

						/*9*/ String n3 = out.toString("UTF-8");

						outKey.set("NEW_MAPPER_ENTITY");
						outVal.set(n3);

						context.write(outKey, outVal);
					}
				}
			}
			catch (Exception ex) {
				LOG.error("Caught Exception", ex);
				context.getCounter(MAPPERCOUNTER.EXCEPTIONS).increment(1);
			}
		}
	}
}