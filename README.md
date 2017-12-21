Out Process [![Build Status](https://travis-ci.org/dyorgio/out-process.svg?branch=master)](https://travis-ci.org/dyorgio/out-process)
===============
A java library to run pieces of code in another JVM.

Usage
-----
For single sync execution:

```java
// Specify JVM options (optional)
OneRunOutProcess oneRun = new OneRunOutProcess("-Xmx32m");
// Run in JVM 1 and exit
int returnCode = oneRun.run(() -> System.out.println("Hello 1"));
// Call in JVM 2 and exit
System.out.println(oneRun.call(() -> System.getProperty("java.version")).getResult());
```

For consecutive/async executions:
```java
// Specify JVM options (optional)
OutProcessExecutorService sharedProcess = new OutProcessExecutorService("-Xmx32m");
// Submit a task to run in external JVM
sharedProcess.submit(new CallableSerializable<String>() {
    @Override
    public String call() {
        return System.setProperty("SHARED_DATA", "EXECUTED");
    }
});
// Get shared data in external JVM and wait (.get())
String value = sharedProcess.submit(new CallableSerializable<String>() {
    @Override
    public String call() {
        return System.getProperty("SHARED_DATA");
    }
}).get();
System.out.println(value);
```
