package acstest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import Zql.ParseException;
import Zql.ZQuery;
import Zql.ZStatement;
import Zql.ZqlParser;
import simpledb.*;

@RunWith(Parameterized.class)
public class DirtyTest {
	@Parameters(name = "{index}: {0}")
	public static Collection<Object[]> data() {
		return Arrays.asList(new Object[][] {
			{ "SELECT BLD FROM acs;" },
			{ "SELECT BLD as units_in_structure, COUNT(ST) as estimate FROM acs GROUP BY BLD;" },
			{ "SELECT BATH as has_bath, COUNT(ST) as ct FROM acs GROUP BY BATH;" },
			{ "SELECT ACR as lotsize, AVG(BDSP) as avg_num_bedrooms FROM acs GROUP BY ACR;" },
			{ "SELECT PSF as has_sub_families, SUM(NP) as num_people FROM acs GROUP BY PSF;" },
			{ "SELECT AVG(NP) as avg_num_people FROM acs;" },
			{ "SELECT BDSP as num_bedrooms, AVG(ACR) as avg_lot_size FROM acs WHERE VEH >= 2 GROUP BY BDSP;" },
			{ "SELECT MIN(YBL) as earliest_built_bucket FROM acs WHERE ACR = 3;" },
			{ "SELECT MIN(RMSP) as min_num_rooms FROM acs WHERE RWAT=2;" },
			{ "SELECT * FROM acs WHERE REFR = 1 AND STOV = 1 AND TEL = 1 AND TOIL = 2;" },
			// simpledb cannot handle predicates vs other columns (only relative to constants)
			{ "SELECT * FROM acs WHERE VEH >= 1 AND VEH <= 5 AND RMSP > 4;" }
		});
	};
	
	@ClassRule public static final AcsTestRule TEST_DB = new AcsTestRule();
	
	private static final int TABLE_SIZE = 5000;
	
	private static DbIterator planQuery(String query, Function<Void, LogicalPlan> planFactory) throws ParseException, TransactionAbortedException, DbException, IOException, ParsingException {
		ZqlParser p = new ZqlParser(new ByteArrayInputStream(query.getBytes("UTF-8")));
		ZStatement s = p.readStatement();
		Parser pp = new Parser(planFactory);
		return pp.handleQueryStatement((ZQuery)s, new TransactionId()).getPhysicalPlan();
	}
	
	private static void drain(DbIterator iter) throws DbException, TransactionAbortedException {
		while (iter.hasNext()) {
			try {
				iter.next();
			} catch(NoSuchElementException e) {
				e.printStackTrace();
			}
		}
	}
	
	private final String query;
	
	public DirtyTest(String query) {
		this.query = query;
	}
	
	
	@BeforeClass
	public static void Before() throws NoSuchElementException, DbException, TransactionAbortedException, IOException {
		System.err.println("Creating new table with dirty data...");
		Database.getCatalog().addTable("acs_small_clean", "", AcsTestRule.SCHEMA);
		Database.getCatalog().addTable("acs_small_dirty", "", AcsTestRule.SCHEMA);
		int oldTblId = Database.getCatalog().getTableId("acs"),
				newTblIdC = Database.getCatalog().getTableId("acs_small_clean"),
				newTblIdD = Database.getCatalog().getTableId("acs_small_dirty");
		
		TransactionId tid = new TransactionId();
		new Insert(tid, new Limit(new SeqScan(tid, oldTblId), TABLE_SIZE), newTblIdC).next();
		tid = new TransactionId();
		new Insert(tid, new Smudge(new SeqScan(tid, newTblIdC), 0.1), newTblIdD).next();
		TableStats.computeStatistics();
		System.err.println("Done.");
	}
	
	@Test
	public void errorTest() throws ParseException, TransactionAbortedException, DbException, IOException, ParsingException {
		System.err.println("Planning queries...");
		DbIterator clean = planQuery(query.replaceAll("acs", "acs_small_clean"), x -> new LogicalPlan());
		DbIterator imputedDirty = planQuery(query.replaceAll("acs", "acs_small_dirty"), x -> new ImputedLogicalPlan(0.0)); 
		DbIterator dirty = planQuery(query.replaceAll("acs", "acs_small_dirty"), x -> new LogicalPlan());
		System.err.println("Done."); 
		
		clean.open();
		imputedDirty.open();
		dirty.open();
		
		clean.print();
		System.out.println();
		imputedDirty.print();
		System.out.println();
		dirty.print();
		
		clean.rewind();
		imputedDirty.rewind();
		dirty.rewind();
		
		double baseErr = clean.error(dirty);
		clean.rewind();
		double imputeErr = clean.error(imputedDirty);
		Assert.assertTrue(
				String.format("Base error should be higher than imputed error. [base: %f, imputed: %f]", baseErr, imputeErr), 
				baseErr >= imputeErr);
	}
	
	@Test
	public void switchTest() throws NoSuchElementException, DbException, TransactionAbortedException, IOException, ParseException, ParsingException {
		final double step = 0.1;
		final String query = this.query.replaceAll("acs", "acs_small_dirty"); 

		System.err.println("Planning queries...");		
		DbIterator imputedDirty = planQuery(query, x -> new ImputedLogicalPlan(0.0));
		imputedDirty.open();
		Assert.assertTrue("Plan does not use drop at lowest quality setting.", imputedDirty.contains(Drop.class));
		
		for (double a = step; a <= 1.0; a += step) {
			final double aa = a;
			imputedDirty = planQuery(query, x -> { return new ImputedLogicalPlan(aa); });
			imputedDirty.open();
			if (imputedDirty.contains(ImputeRandom.class) || imputedDirty.contains(ImputeRegressionTree.class)) {
				System.err.format("Switched from drop to impute at alpha %f.\n", aa);
				return;
			}
			imputedDirty.close();
			Assert.assertTrue("Plan contains neither drop nor impute.", imputedDirty.contains(Drop.class));
		}
		Assert.fail("Never switched from drop to impute.");
	}
	
	@Test
	public void TimeImputation() throws NoSuchElementException, DbException, TransactionAbortedException, IOException, ParseException, ParsingException {
		final double step = 0.1;
		final String query = this.query.replaceAll("acs", "acs_small_dirty"); 
		final Duration[] times = new Duration[(int)(1.0 / step) + 1];
		Instant start, end;
		
		System.err.println("Planning queries...");
		
		start = Instant.now();
		DbIterator imputedDirty = planQuery(query, x -> new ImputedLogicalPlan(0.0));
		imputedDirty.open();
		drain(imputedDirty);
		end = Instant.now();
		times[0] = Duration.between(start, end);
		
		int t = 1;
		for (double a = step; a <= 1.0; a += step) {
			final double aa = a;
			imputedDirty = planQuery(query, x -> { return new ImputedLogicalPlan(aa); });
			
			start = Instant.now();
			imputedDirty.open();
			drain(imputedDirty);
			imputedDirty.close();
			end = Instant.now();
			times[t] = Duration.between(start, end);
			
			t++;
		}
		
		Assert.assertTrue(
				String.format("Imputation should take longer than dropping. [drop: %s, impute: %s]", times[0], times[times.length - 1]), 
				times[0].compareTo(times[times.length - 1]) < 0);
	}
}
