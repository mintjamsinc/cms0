# cms0
This is a simplified implementation of the Content Repository for Java Technology API Version 2.0 (JSR 283) and is one of the components used in MintJams Content Repository 7.

## FileCache Builder

`FileCache` provides a `Builder` for creating cache entries from streamed
content. The builder creates a temporary file under the supplied temporary
directory. If `build()` is not invoked, the temporary file will automatically be
removed when the builder is closed or garbage-collected. Because the builder
implements `AutoCloseable`, it should be used in a try-with-resources statement:

```java
try (FileCache.Builder builder = FileCache.newBuilder(tempDir)) {
    builder.write(data);
    FileCache cache = builder.build();
    // use cache
}
```

If `build()` is never called, closing the builder (explicitly or implicitly)
ensures that the temporary file is deleted.
