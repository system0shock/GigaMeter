# JMeter AI - Supported Elements

This document lists all the JMeter elements that are supported by the JMeter AI extension.

## Samplers

- **HTTP Request** (`httpsampler`)
  - Implementation: `org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy`
  - GUI Class: `org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui`
  - Synonyms: http sampler, http request, web request, http, request, http request sampler

## Controllers

- **Loop Controller** (`loopcontroller`)
  - Implementation: `org.apache.jmeter.control.LoopController`
  - GUI Class: `org.apache.jmeter.control.gui.LoopControlPanel`
  - Synonyms: loop controller, loop, repeat controller

- **If Controller** (`ifcontroller`)
  - Implementation: `org.apache.jmeter.control.IfController`
  - GUI Class: `org.apache.jmeter.control.gui.IfControllerPanel`
  - Synonyms: if controller, conditional controller, condition

- **While Controller** (`whilecontroller`)
  - Implementation: `org.apache.jmeter.control.WhileController`
  - GUI Class: `org.apache.jmeter.control.gui.WhileControllerGui`
  - Synonyms: while controller, while loop, while

- **Transaction Controller** (`transactioncontroller`)
  - Implementation: `org.apache.jmeter.control.TransactionController`
  - GUI Class: `org.apache.jmeter.control.gui.TransactionControllerGui`
  - Synonyms: transaction controller, transaction, tx controller

- **Runtime Controller** (`runtimecontroller`)
  - Implementation: `org.apache.jmeter.control.RunTime`
  - GUI Class: `org.apache.jmeter.control.gui.RunTimeGui`
  - Synonyms: runtime controller, runtime, timed controller

## Config Elements

- **CSV Data Set** (`csvdataset`)
  - Implementation: `org.apache.jmeter.config.CSVDataSet`
  - GUI Class: `org.apache.jmeter.testbeans.gui.TestBeanGUI`
  - Synonyms: csv data set, csv, data set, csv config

- **HTTP Header Manager** (`headermanager`)
  - Implementation: `org.apache.jmeter.protocol.http.control.HeaderManager`
  - GUI Class: `org.apache.jmeter.protocol.http.gui.HeaderPanel`
  - Synonyms: header manager, http headers, headers

## Thread Groups

- **Thread Group** (`threadgroup`)
  - Implementation: `org.apache.jmeter.threads.ThreadGroup`
  - GUI Class: `org.apache.jmeter.threads.gui.ThreadGroupGui`
  - Synonyms: thread group, users, virtual users

## Assertions

- **Response Assertion** (`responseassert`)
  - Implementation: `org.apache.jmeter.assertions.ResponseAssertion`
  - GUI Class: `org.apache.jmeter.assertions.gui.AssertionGui`
  - Synonyms: response assertion, response validator, text assertion

- **JSON Path Assertion** (`jsonassertion`)
  - Implementation: `org.apache.jmeter.assertions.JSONPathAssertion`
  - GUI Class: `org.apache.jmeter.assertions.gui.JSONPathAssertionGui`
  - Synonyms: json assertion, json path assertion, json validator

- **Duration Assertion** (`durationassertion`)
  - Implementation: `org.apache.jmeter.assertions.DurationAssertion`
  - GUI Class: `org.apache.jmeter.assertions.gui.DurationAssertionGui`
  - Synonyms: duration assertion, response time assertion, time assertion

- **Size Assertion** (`sizeassertion`)
  - Implementation: `org.apache.jmeter.assertions.SizeAssertion`
  - GUI Class: `org.apache.jmeter.assertions.gui.SizeAssertionGui`
  - Synonyms: size assertion, response size assertion, byte size assertion

- **XPath Assertion** (`xpathassertion`)
  - Implementation: `org.apache.jmeter.assertions.XPathAssertion`
  - GUI Class: `org.apache.jmeter.assertions.gui.XPathAssertionGui`
  - Synonyms: xpath assertion, xml assertion, xml validator

## Timers

- **Constant Timer** (`constanttimer`)
  - Implementation: `org.apache.jmeter.timers.ConstantTimer`
  - GUI Class: `org.apache.jmeter.timers.gui.ConstantTimerGui`
  - Synonyms: constant timer, fixed timer, delay, wait, timer

- **Uniform Random Timer** (`uniformrandomtimer`)
  - Implementation: `org.apache.jmeter.timers.UniformRandomTimer`
  - GUI Class: `org.apache.jmeter.timers.gui.UniformRandomTimerGui`
  - Synonyms: uniform random timer, random timer, uniform timer

- **Gaussian Random Timer** (`gaussianrandomtimer`)
  - Implementation: `org.apache.jmeter.timers.GaussianRandomTimer`
  - GUI Class: `org.apache.jmeter.timers.gui.GaussianRandomTimerGui`
  - Synonyms: gaussian random timer, gaussian timer, normal distribution timer

- **Poisson Random Timer** (`poissonrandomtimer`)
  - Implementation: `org.apache.jmeter.timers.PoissonRandomTimer`
  - GUI Class: `org.apache.jmeter.timers.gui.PoissonRandomTimerGui`
  - Synonyms: poisson random timer, poisson timer

## Extractors

- **Regular Expression Extractor** (`regexextractor`)
  - Implementation: `org.apache.jmeter.extractor.RegexExtractor`
  - GUI Class: `org.apache.jmeter.extractor.gui.RegexExtractorGui`
  - Synonyms: regex extractor, regular expression extractor, regex, regexp

- **XPath Extractor** (`xpathextractor`)
  - Implementation: `org.apache.jmeter.extractor.XPathExtractor`
  - GUI Class: `org.apache.jmeter.extractor.gui.XPathExtractorGui`
  - Synonyms: xpath extractor, xml extractor, xpath

- **JSON Path Extractor** (`jsonpathextractor`)
  - Implementation: `org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor`
  - GUI Class: `org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui`
  - Synonyms: json extractor, jsonpath extractor, json path extractor, jsonpath

- **Boundary Extractor** (`boundaryextractor`)
  - Implementation: `org.apache.jmeter.extractor.BoundaryExtractor`
  - GUI Class: `org.apache.jmeter.extractor.gui.BoundaryExtractorGui`
  - Synonyms: boundary extractor, text extractor, boundary

## Listeners

- **View Results Tree** (`viewresultstree`)
  - Implementation: `org.apache.jmeter.visualizers.ViewResultsFullVisualizer`
  - GUI Class: `org.apache.jmeter.visualizers.ViewResultsFullVisualizer`
  - Synonyms: view results tree, results tree, view results, results viewer

- **Aggregate Report** (`aggregatereport`)
  - Implementation: `org.apache.jmeter.report.gui.AggregateReportGui`
  - GUI Class: `org.apache.jmeter.report.gui.AggregateReportGui`
  - Synonyms: aggregate report, summary report, statistics, stats
