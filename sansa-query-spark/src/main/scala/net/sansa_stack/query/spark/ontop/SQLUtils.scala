package net.sansa_stack.query.spark.ontop

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import org.apache.jena.graph.NodeFactory

import net.sansa_stack.rdf.common.partition.core.RdfPartitionComplex

/**
 * @author Lorenz Buehmann
 */
object SQLUtils {

  def escapeTablename(path: String): String =
    "\"" +
      URLEncoder.encode(path, StandardCharsets.UTF_8.toString)
        .toLowerCase
        .replace('%', 'P')
        .replace('.', 'C')
        .replace("-", "dash") +
      "\""

  def createTableName(p: RdfPartitionComplex): String = {
    val pred = p.predicate

    // For now let's just use the full predicate as the uri
    // val predPart = pred.substring(pred.lastIndexOf("/") + 1)
    val predPart = pred
    val pn = NodeFactory.createURI(p.predicate)

    val dt = p.datatype
    val dtPart = if (dt != null && !dt.isEmpty) "_" + dt.substring(dt.lastIndexOf("/") + 1) else ""
    val langPart = if (p.langTagPresent) "_lang" else ""

    val tableName = predPart + dtPart + langPart // .replace("#", "__").replace("-", "_")

    tableName
  }

}