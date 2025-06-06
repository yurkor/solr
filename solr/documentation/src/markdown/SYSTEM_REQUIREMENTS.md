# System Requirements

Apache Solr runs on Java 21 or greater.

It is also recommended to always use the latest update version of your
Java VM, because bugs may affect Solr. An overview of known JVM bugs
can be found on https://cwiki.apache.org/confluence/display/lucene/JavaBugs

With all Java versions it is strongly recommended to not use experimental
`-XX` JVM options.

CPU, disk and memory requirements are based on the many choices made in
implementing Solr (document size, number of documents, and number of
hits retrieved to name a few). The benchmarks page has some information
related to performance on particular platforms.
