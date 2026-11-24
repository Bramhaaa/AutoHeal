package com.autoheal.service;

import java.util.List;

/**
 * Curated seed corpus of 30 common Java/Spring Boot incident write-ups (FR-2.3). Intentionally
 * distinct from the 4 Playground demo scenarios to avoid evaluation leakage.
 */
public final class IncidentMemorySeedCatalog {

  public record SeedIncident(String errorSignature, String resolutionSummary) {}

  private static final List<SeedIncident> ENTRIES =
      List.of(
          new SeedIncident(
              "java.lang.NullPointerException: Cannot invoke method on null optional DTO field",
              "Guard nullable request/DTO fields before dereferencing or default with Objects.requireNonNullElse."),
          new SeedIncident(
              "java.lang.IndexOutOfBoundsException: Index out of bounds when slicing list for page",
              "Clamp endIndex to Math.min(startIndex + pageSize, items.size()) and return empty list when startIndex >= items.size()."),
          new SeedIncident(
              "java.lang.ArithmeticException: Non-terminating decimal expansion in BigDecimal.divide",
              "Specify explicit scale and RoundingMode.HALF_UP in BigDecimal.divide(divisor, 2, RoundingMode.HALF_UP)."),
          new SeedIncident(
              "AssertionFailedError: BigDecimal equals failed for 10.0 vs 10.00",
              "Compare monetary BigDecimal values using compareTo(...) == 0 or normalize scale with setScale(2, RoundingMode.HALF_UP)."),
          new SeedIncident(
              "java.util.ConcurrentModificationException while removing items in for-each loop",
              "Replace enhanced for-loop removal with Collection.removeIf(...) or Iterator.remove()."),
          new SeedIncident(
              "Lost updates under multithreaded increment/decrement on shared int counter",
              "Replace non-atomic read-modify-write int field with AtomicInteger or synchronize critical section."),
          new SeedIncident(
              "org.hibernate.query.SemanticException: Could not interpret path expression in JPQL",
              "Verify JPQL query uses Java entity field names (camelCase) rather than SQL column names (snake_case)."),
          new SeedIncident(
              "org.hibernate.LazyInitializationException: could not initialize proxy - no Session",
              "Fetch required association eagerly with JOIN FETCH in the repository query or map inside a @Transactional boundary."),
          new SeedIncident(
              "com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException on JSON payload",
              "Annotate DTO with @JsonIgnoreProperties(ignoreUnknown = true) or map the missing JSON property explicitly."),
          new SeedIncident(
              "java.time.format.DateTimeParseException: Text could not be parsed at index 0",
              "Use DateTimeFormatter.ISO_INSTANT / ISO_OFFSET_DATE_TIME consistently and validate input format before parsing."),
          new SeedIncident(
              "java.lang.NumberFormatException: For input string with whitespace or empty value",
              "Trim string input and guard against blank strings before calling Integer.parseInt or Long.parseLong."),
          new SeedIncident(
              "java.util.NoSuchElementException: No value present on Optional.get()",
              "Replace unsafe Optional.get() with orElse(...), orElseGet(...), or orElseThrow(typedException)."),
          new SeedIncident(
              "java.lang.IllegalStateException: Duplicate key in Collectors.toMap",
              "Provide a merge function to Collectors.toMap(keyFn, valFn, (existing, replacement) -> replacement)."),
          new SeedIncident(
              "java.lang.UnsupportedOperationException when mutating List.of() or Arrays.asList()",
              "Copy immutable list into new ArrayList<>(source) before sorting or adding elements."),
          new SeedIncident(
              "java.lang.ClassCastException: LinkedHashMap cannot be cast to domain DTO",
              "Pass explicit TypeReference<List<MyDto>> to ObjectMapper.readValue instead of raw List.class."),
          new SeedIncident(
              "AssertionFailedError: HashSet/HashMap lookup fails for equal value objects",
              "Implement both equals() and hashCode() consistently (or convert value class to a Java record)."),
          new SeedIncident(
              "String comparison using == fails for dynamically constructed strings",
              "Replace reference equality (==) with Objects.equals(a, b) or a.equals(b)."),
          new SeedIncident(
              "Integer caching bug: == returns false for boxed Integer values > 127",
              "Use Objects.equals(idA, idB) instead of == when comparing boxed Integer or Long references."),
          new SeedIncident(
              "Off-by-one loop condition <= array.length throws ArrayIndexOutOfBoundsException",
              "Change loop upper bound from i <= arr.length to i < arr.length."),
          new SeedIncident(
              "Off-by-one inclusive/exclusive date range drops boundary day records",
              "Use !date.isBefore(start) && !date.isAfter(end) for inclusive bounds or half-open [start, end) consistently."),
          new SeedIncident(
              "java.lang.StackOverflowError in bidirectional JPA entity toString/equals",
              "Exclude lazy parent/child collections from toString(), equals(), and hashCode()."),
          new SeedIncident(
              "Spring @Transactional self-invocation bypasses proxy and skips rollback",
              "Move transactional method to a separate Spring bean or inject the collaborator so calls go through the proxy."),
          new SeedIncident(
              "java.lang.IllegalArgumentException: Comparison method violates its general contract",
              "Avoid subtraction (a - b) in Comparator (overflow risk); use Integer.compare(a, b) or Comparator.comparing(...)."),
          new SeedIncident(
              "java.util.regex.PatternSyntaxException: Dangling meta character in String.split",
              "Escape regex metacharacters like '.' or '|' using Pattern.quote(delimiter) before calling String.split."),
          new SeedIncident(
              "Division truncates to zero when dividing two integers before assigning to double",
              "Cast numerator to double ((double) count / total) and guard against total == 0."),
          new SeedIncident(
              "Stream already operated upon or closed (IllegalStateException)",
              "Avoid reusing a Stream instance across terminal operations; create a fresh stream from the collection."),
          new SeedIncident(
              "Missing break in switch statement causes unintended fall-through",
              "Convert legacy switch statement to Java 21 arrow switch expression (->) to eliminate fall-through bugs."),
          new SeedIncident(
              "Timezone drift when converting LocalDateTime to Instant using systemDefault()",
              "Standardize on Instant / ZoneOffset.UTC rather than ZoneId.systemDefault() in business logic and tests."),
          new SeedIncident(
              "SQL IntegrityConstraintViolationException on duplicate insert under retry",
              "Make insert idempotent via ON CONFLICT DO NOTHING or check/handle unique key constraint cleanly."),
          new SeedIncident(
              "Resource leak or unclosed InputStream/Reader causes file lock on Windows/Linux",
              "Wrap streams, readers, and JDBC resources in try-with-resources blocks."));

  private IncidentMemorySeedCatalog() {}

  public static List<SeedIncident> entries() {
    return ENTRIES;
  }
}
