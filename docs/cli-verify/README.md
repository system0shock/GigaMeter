# CLI transport — verification harness

Faithful OS-level verification of the Stage-1 subprocess fixes. Python's `subprocess` uses the
same OS pipe buffers (~64 KiB) and blocking-read semantics as the JVM, so the two-channel deadlock
and the dead-timeout bug reproduce exactly as they would in Java. This compares the prototype's
read strategy (OLD) against `CliTransport` (NEW).

## Run

```
python3 verify.py        # subprocess behavior: deadlock, timeout, exit codes
python3 verify_logic.py  # JSON parser (Cyrillic), Windows cmd-wrapper rule, quote-aware args
```

## Expected result (`verify.py`)

| Scenario | OLD | NEW |
|---|---|---|
| normal (JSON, exit 0) | ok | ok |
| ~2 MB on stderr (deadlock) | **HANG** | finishes, stderr fully drained |
| hung CLI (sleep 120) | **HANG** | times out at deadline, process tree killed |
| failing CLI (exit 3) | error surfaced | error surfaced |

The Java `CliTransport` mirrors this exact algorithm; `CliTransportTest` asserts the same behavior
against a fake CLI running in a separate JVM (real OS pipes).

## Real qwen end-to-end (on the machine where qwen is installed)

```
mvn test-compile
java -cp "target/classes;target/test-classes;%JMETER_HOME%/lib/*" \
     org.gigameter.jmeter.ai.service.cli.CliSmokeMain qwen "что делает Transaction Controller?"
```
