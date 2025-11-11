package org.mintjams.rt.jcr.internal;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.jcr.Repository;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Automatic test runner that executes JCR tests when the bundle starts.
 * Test results are written to a file in the configured output directory.
 */
@Component(immediate = true)
public class TestRunner {

	private Repository repository;

	@Reference
	public void setRepository(Repository repository) {
		this.repository = repository;
	}

	@Activate
	public void activate() {
		// Run tests in a separate thread to avoid blocking bundle activation
		Thread testThread = new Thread(() -> {
			try {
				// Wait a bit for all services to be available
				Thread.sleep(2000);
				runTests();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		testThread.setName("JCR-Test-Runner");
		testThread.start();
	}

	private void runTests() {
		System.out.println("========================================");
		System.out.println("Starting JCR Tests");
		System.out.println("========================================");

		// Test classes to run
		Class<?>[] testClasses = {
			JcrSessionTest.class,
			JcrRepositoryTest.class,
			JcrNamespaceRegistryTest.class
		};

		// Run tests
		JUnitCore junit = new JUnitCore();
		Result result = junit.run(testClasses);

		// Print results to console
		printResultsToConsole(result);

		// Write results to file
		writeResultsToFile(result);

		System.out.println("========================================");
		System.out.println("JCR Tests Completed");
		System.out.println("========================================");
	}

	private void printResultsToConsole(Result result) {
		System.out.println("\n========== Test Results ==========");
		System.out.println("Tests run: " + result.getRunCount());
		System.out.println("Tests passed: " + (result.getRunCount() - result.getFailureCount()));
		System.out.println("Tests failed: " + result.getFailureCount());
		System.out.println("Tests ignored: " + result.getIgnoreCount());
		System.out.println("Time elapsed: " + result.getRunTime() + "ms");
		System.out.println("Success: " + result.wasSuccessful());

		if (!result.wasSuccessful()) {
			System.out.println("\n========== Failures ==========");
			for (Failure failure : result.getFailures()) {
				System.out.println("\nTest: " + failure.getTestHeader());
				System.out.println("Message: " + failure.getMessage());
				System.out.println("Trace:\n" + failure.getTrace());
			}
		}
		System.out.println("==================================\n");
	}

	private void writeResultsToFile(Result result) {
		try {
			// Determine output directory
			String outputDir = System.getProperty("jcr.test.output.dir", "test-results");
			File dir = new File(outputDir);
			if (!dir.exists()) {
				dir.mkdirs();
			}

			// Create filename with timestamp
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
			String timestamp = dateFormat.format(new Date());
			File outputFile = new File(dir, "jcr-test-results-" + timestamp + ".txt");

			// Write results
			try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
				writer.println("JCR 2.0 Implementation Test Results");
				writer.println("===================================");
				writer.println("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
				writer.println();
				writer.println("Summary:");
				writer.println("  Tests run: " + result.getRunCount());
				writer.println("  Tests passed: " + (result.getRunCount() - result.getFailureCount()));
				writer.println("  Tests failed: " + result.getFailureCount());
				writer.println("  Tests ignored: " + result.getIgnoreCount());
				writer.println("  Time elapsed: " + result.getRunTime() + "ms");
				writer.println("  Success: " + result.wasSuccessful());
				writer.println();

				if (!result.wasSuccessful()) {
					writer.println("Failures:");
					writer.println("=========");
					for (Failure failure : result.getFailures()) {
						writer.println();
						writer.println("Test: " + failure.getTestHeader());
						writer.println("Message: " + failure.getMessage());
						writer.println();
						writer.println("Stack Trace:");
						writer.println(failure.getTrace());
						writer.println("-----------------------------------");
					}
				} else {
					writer.println("All tests passed successfully!");
				}

				// Write HTML report as well
				writeHtmlReport(result, dir, timestamp);
			}

			System.out.println("Test results written to: " + outputFile.getAbsolutePath());

		} catch (Exception e) {
			System.err.println("Failed to write test results: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void writeHtmlReport(Result result, File dir, String timestamp) {
		try {
			File htmlFile = new File(dir, "jcr-test-results-" + timestamp + ".html");

			try (PrintWriter writer = new PrintWriter(new FileWriter(htmlFile))) {
				writer.println("<!DOCTYPE html>");
				writer.println("<html>");
				writer.println("<head>");
				writer.println("  <meta charset=\"UTF-8\">");
				writer.println("  <title>JCR Test Results</title>");
				writer.println("  <style>");
				writer.println("    body { font-family: Arial, sans-serif; margin: 20px; }");
				writer.println("    h1 { color: #333; }");
				writer.println("    .summary { background: #f5f5f5; padding: 15px; border-radius: 5px; margin: 20px 0; }");
				writer.println("    .success { color: green; font-weight: bold; }");
				writer.println("    .failure { color: red; font-weight: bold; }");
				writer.println("    .stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 15px; margin: 20px 0; }");
				writer.println("    .stat { background: white; padding: 15px; border: 1px solid #ddd; border-radius: 5px; }");
				writer.println("    .stat-value { font-size: 24px; font-weight: bold; color: #007bff; }");
				writer.println("    .stat-label { color: #666; font-size: 14px; }");
				writer.println("    .failure-detail { background: #fff3cd; padding: 15px; margin: 10px 0; border-left: 4px solid #ffc107; }");
				writer.println("    .trace { background: #f8f9fa; padding: 10px; overflow-x: auto; font-family: monospace; font-size: 12px; }");
				writer.println("  </style>");
				writer.println("</head>");
				writer.println("<body>");
				writer.println("  <h1>JCR 2.0 Implementation Test Results</h1>");
				writer.println("  <div class=\"summary\">");
				writer.println("    <p><strong>Date:</strong> " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "</p>");
				writer.println("    <p><strong>Status:</strong> <span class=\"" + (result.wasSuccessful() ? "success" : "failure") + "\">");
				writer.println(result.wasSuccessful() ? "PASSED" : "FAILED");
				writer.println("</span></p>");
				writer.println("  </div>");

				writer.println("  <div class=\"stats\">");
				writer.println("    <div class=\"stat\">");
				writer.println("      <div class=\"stat-value\">" + result.getRunCount() + "</div>");
				writer.println("      <div class=\"stat-label\">Tests Run</div>");
				writer.println("    </div>");
				writer.println("    <div class=\"stat\">");
				writer.println("      <div class=\"stat-value\" style=\"color: green;\">" + (result.getRunCount() - result.getFailureCount()) + "</div>");
				writer.println("      <div class=\"stat-label\">Passed</div>");
				writer.println("    </div>");
				writer.println("    <div class=\"stat\">");
				writer.println("      <div class=\"stat-value\" style=\"color: red;\">" + result.getFailureCount() + "</div>");
				writer.println("      <div class=\"stat-label\">Failed</div>");
				writer.println("    </div>");
				writer.println("  </div>");

				if (!result.wasSuccessful()) {
					writer.println("  <h2>Failures</h2>");
					for (Failure failure : result.getFailures()) {
						writer.println("  <div class=\"failure-detail\">");
						writer.println("    <h3>" + escapeHtml(failure.getTestHeader()) + "</h3>");
						writer.println("    <p><strong>Message:</strong> " + escapeHtml(failure.getMessage()) + "</p>");
						writer.println("    <details>");
						writer.println("      <summary>Stack Trace</summary>");
						writer.println("      <pre class=\"trace\">" + escapeHtml(failure.getTrace()) + "</pre>");
						writer.println("    </details>");
						writer.println("  </div>");
					}
				} else {
					writer.println("  <p style=\"color: green; font-size: 18px;\">✓ All tests passed successfully!</p>");
				}

				writer.println("  <div style=\"margin-top: 30px; padding-top: 20px; border-top: 1px solid #ddd; color: #666; font-size: 12px;\">");
				writer.println("    <p>Time elapsed: " + result.getRunTime() + "ms</p>");
				writer.println("  </div>");
				writer.println("</body>");
				writer.println("</html>");
			}

			System.out.println("HTML report written to: " + htmlFile.getAbsolutePath());

		} catch (Exception e) {
			System.err.println("Failed to write HTML report: " + e.getMessage());
		}
	}

	private String escapeHtml(String text) {
		if (text == null) return "";
		return text.replace("&", "&amp;")
				   .replace("<", "&lt;")
				   .replace(">", "&gt;")
				   .replace("\"", "&quot;")
				   .replace("'", "&#39;");
	}
}
