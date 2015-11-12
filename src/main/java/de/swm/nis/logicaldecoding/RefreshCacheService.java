package de.swm.nis.logicaldecoding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.vividsolutions.jts.geom.Envelope;

import de.swm.nis.logicaldecoding.dataaccess.ChangeSetDAO;
import de.swm.nis.logicaldecoding.dataaccess.ChangeSetFetcher;
import de.swm.nis.logicaldecoding.gwc.GWCInvalidator;
import de.swm.nis.logicaldecoding.parser.LogicalDecodingTestPluginParser;
import de.swm.nis.logicaldecoding.parser.Row;
import de.swm.nis.logicaldecoding.tracktable.TrackTablePublisher;



@Service
public class RefreshCacheService {

	private static final Logger log = LoggerFactory.getLogger(RefreshCacheService.class);

	@Autowired
	private ChangeSetFetcher changesetFetcher;

	@Autowired
	private GWCInvalidator gwcInvalidator;

	@Autowired
	private LogicalDecodingTestPluginParser parser;

	@Autowired
	private TrackTablePublisher trackTablePublisher;

	@Value("${scheduling.numChangesToFetch}")
	private int numChangestoFetch;

	@Value("${scheduling.interval}")
	private int SchedulingDelay;

	@Value("#{'${filter.includeSchema}'.split(',')}")
	private List<String> schemasToInclude;



	@Scheduled(fixedDelayString = "${scheduling.interval}")
	public void refreshCache() {

		long pending_changes = changesetFetcher.peek("repslot_test");

		log.info("Changes pending to process: " + pending_changes);

		if (pending_changes > 0) {

			log.info("fetching up to " + numChangestoFetch + " changes...");
			Collection<ChangeSetDAO> changes = changesetFetcher.fetch("repslot_test", numChangestoFetch);
			Collection<Row> rows = new ArrayList<Row>();

			for (ChangeSetDAO change : changes) {
				log.info(change.toString());
				Row row = parser.parseLogLine(change.getData());
				// This is a change to consider
				if (row != null) {
					rows.add(row);
				}
			}

			log.info("Checking relevance...");

			Predicate<Row> predicate = new Predicate<Row>() {
				@Override
				public boolean apply(Row input) {
					return schemasToInclude.contains(input.getSchemaName());
				}
			};

			Collection<Row> relevantRows = Collections2.filter(rows, predicate);

			log.info("Calculating affected Regions...");
			Collection<Envelope> envelopes = findAffectedRegion(relevantRows);
			log.info("sending " + envelopes.size() + " Seed Requests to geoWebCache...");
			for (Envelope env : envelopes) {
				log.info(env.toString());
				gwcInvalidator.postSeedRequest(env);
			}

			log.info("publishing change metadata to track table...");
			trackTablePublisher.publish(relevantRows);
		}
	}



	private Collection<Envelope> findAffectedRegion(Collection<Row> rows) {
		Collection<Envelope> envelopes = new ArrayList<Envelope>();
		for (Row row : rows) {
			if (row != null) {
				envelopes.add(row.getEnvelope());
			}
		}
		return envelopes;
	}

}
