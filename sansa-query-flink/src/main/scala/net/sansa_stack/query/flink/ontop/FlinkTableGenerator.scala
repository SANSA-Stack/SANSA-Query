package net.sansa_stack.query.flink.ontop

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import org.apache.flink.api.scala.DataSet
import org.apache.flink.table.api.bridge.scala.BatchTableEnvironment

import net.sansa_stack.query.common.ontop.{BlankNodeStrategy, SQLUtils}
import net.sansa_stack.rdf.common.partition.core.RdfPartitionComplex

/**
 * @author Lorenz Buehmann
 */
class FlinkTableGenerator(env: BatchTableEnvironment,
                          blankNodeStrategy: BlankNodeStrategy.Value = BlankNodeStrategy.Table) {

  val logger = com.typesafe.scalalogging.Logger(classOf[FlinkTableGenerator])

  /**
   * Creates and registers a Flink table p(s,o) for each partition.
   *
   * Note: partitions with string literals as object will be kept into a single table per property and a 3rd column for the
   * (optional) language tag is used instead.
   *
   * @param partitions the partitions
   */
  def createAndRegisterFlinkTables(partitions: Map[RdfPartitionComplex, DataSet[Product]]): Unit = {

    // register the lang tagged RDDs as a single table:
    // we have to merge the RDDs of all languages per property first, otherwise we would always replace it by another
    // language
    partitions
      .filter(_._1.lang.nonEmpty)
      .map { case (p, ds) => (p.predicate, p, ds) }
      .groupBy(_._1)
      .map { case (k, v) =>
        val ds = v.map(_._3).reduce((a, b) => a.union(b))
        val p = v.head._2
        (p, ds)
      }

      .map { case (p, ds) => (SQLUtils.createTableName(p, blankNodeStrategy), p, ds) }
      .groupBy(_._1)
      .map(map => map._2.head)
      .map(e => (e._2, e._3))
      .foreach { case (p, ds) => createFlinkTable(p, ds) }

    // register the non-lang tagged RDDs as table
    partitions
      .filter(_._1.lang.isEmpty)
      .map { case (p, ds) => (SQLUtils.createTableName(p, blankNodeStrategy), p, ds) }
      .groupBy(_._1)
      .map(map => map._2.head)
      .map(e => (e._2, e._3))

      .foreach {
        case (p, ds) => createFlinkTable(p, ds)
      }
  }

  /**
   * creates a Flink table for each RDF partition
   */
  private def createFlinkTable(p: RdfPartitionComplex, dataSet: DataSet[Product]) = {

    val name = SQLUtils.createTableName(p, blankNodeStrategy)
    logger.debug(s"creating Spark table ${escapeTablename(name)}")

    val scalaSchema = p.layout.schema
    env.createTemporaryView(escapeTablename(name), dataSet)
  }

  private def escapeTablename(path: String): String =
    URLEncoder.encode(path, StandardCharsets.UTF_8.toString)
      .toLowerCase
      .replace('%', 'P')
      .replace('.', 'C')
      .replace("-", "dash")

}

object FlinkTableGenerator {
  def apply(env: BatchTableEnvironment): FlinkTableGenerator = new FlinkTableGenerator(env)

  def apply(env: BatchTableEnvironment,
            blankNodeStrategy: BlankNodeStrategy.Value): FlinkTableGenerator =
    new FlinkTableGenerator(env, blankNodeStrategy)
}


