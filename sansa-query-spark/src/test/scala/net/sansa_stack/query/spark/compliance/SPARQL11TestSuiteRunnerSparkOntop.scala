package net.sansa_stack.query.spark.compliance

import java.util.Properties

import scala.collection.JavaConverters._
import scala.collection.mutable

import com.google.common.collect.ImmutableMap
import it.unibz.inf.ontop.answering.reformulation.input.{ConstructQuery, ConstructTemplate}
import it.unibz.inf.ontop.exception.OntopInternalBugException
import it.unibz.inf.ontop.model.`type`.TypeFactory
import it.unibz.inf.ontop.model.term._
import it.unibz.inf.ontop.model.term.impl.DBConstantImpl
import it.unibz.inf.ontop.model.vocabulary.XSD
import it.unibz.inf.ontop.utils.ImmutableCollectors
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.query._
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.ResultSetStream
import org.apache.jena.sparql.engine.binding.{Binding, BindingFactory}
import org.apache.jena.sparql.graph.GraphFactory
import org.apache.jena.sparql.resultset.SPARQLResult
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.eclipse.rdf4j.model.{IRI, Literal}
import org.eclipse.rdf4j.query.algebra.{Extension, ExtensionElem, ProjectionElem, ValueConstant, ValueExpr}

import net.sansa_stack.query.spark.ontop.{OntopQueryRewrite, OntopSPARQLEngine}
import net.sansa_stack.rdf.common.partition.core.{RdfPartitionComplex, RdfPartitionerComplex}
import net.sansa_stack.rdf.spark.partition.core.RdfPartitionUtilsSpark

/**
 * SPARQL 1.1 test suite runner for Ontop-based SPARQL-to-SQL implementation on Apache Spark.
 *
 *
 * @author Lorenz Buehmann
 */
class SPARQL11TestSuiteRunnerSparkOntop
  extends SPARQL11TestSuiteRunnerSpark {

  override lazy val IGNORE = Set(/* AGGREGATES */
    // TODO: support GROUP_CONCAT
    aggregatesManifest + "agg-groupconcat-01", aggregatesManifest + "agg-groupconcat-02", aggregatesManifest + "agg-groupconcat-03", // TODO: support IF
    aggregatesManifest + "agg-err-02", /* BINDINGS
			 */
    // TODO: fix it (UNDEF involves the notion of COMPATIBILITY when joining)
    bindingsManifest + "values8", bindingsManifest + "values5", /* FUNCTIONS */
    // bnode not supported in SPARQL transformation
    functionsManifest + "bnode01", functionsManifest + "bnode02", // the SI does not preserve the original timezone
    functionsManifest + "hours", functionsManifest + "day", // not supported in SPARQL transformation
    functionsManifest + "if01", functionsManifest + "if02",
    functionsManifest + "in01", functionsManifest + "in02",
    functionsManifest + "iri01", // not supported in H2 transformation
    functionsManifest + "md5-01", functionsManifest + "md5-02", // The SI does not support IRIs as ORDER BY conditions
    functionsManifest + "plus-1", functionsManifest + "plus-2", // SHA1 is not supported in H2
    functionsManifest + "sha1-01", functionsManifest + "sha1-02", // SHA512 is not supported in H2
    functionsManifest + "sha512-01", functionsManifest + "sha512-02",
    functionsManifest + "strdt01", functionsManifest + "strdt02", functionsManifest + "strdt03",
    functionsManifest + "strlang01", functionsManifest + "strlang02", functionsManifest + "strlang03",
    functionsManifest + "timezone", // TZ is not supported in H2
    functionsManifest + "tz", /* CONSTRUCT not supported yet */
    // Projection cannot be cast to Reduced in rdf4j
    constructManifest + "constructwhere01", constructManifest + "constructwhere02", constructManifest + "constructwhere03", // problem importing dataset
    constructManifest + "constructwhere04", /* CSV */
    // Sorting by IRI is not supported by the SI
    csvTscResManifest + "tsv01", csvTscResManifest + "tsv02", // different format for number and not supported custom datatype
    csvTscResManifest + "tsv03",
    /* GROUPING */
    // Multi-typed COALESCE as grouping condition TODO: support it
    groupingManifest + "group04",
    /* NEGATION not supported yet */
    negationManifest + "subset-by-exclusion-nex-1", negationManifest + "temporal-proximity-by-exclusion-nex-1", negationManifest + "subset-01", negationManifest + "subset-02", negationManifest + "set-equals-1", negationManifest + "subset-03", negationManifest + "exists-01", negationManifest + "exists-02", // DISABLED DUE TO ORDER OVER IRI
    negationManifest + "full-minuend", negationManifest + "partial-minuend", // TODO: enable it
    negationManifest + "full-minuend-modified", negationManifest + "partial-minuend-modified",
    /* EXISTS not supported yet */
    existsManifest + "exists01", existsManifest + "exists02", existsManifest + "exists03", existsManifest + "exists04", existsManifest + "exists05", /* PROPERTY PATH */
    // Not supported: ArbitraryLengthPath
    propertyPathManifest + "pp02", // wrong result, unexpected binding
    propertyPathManifest + "pp06", propertyPathManifest + "pp12", propertyPathManifest + "pp14", propertyPathManifest + "pp16", propertyPathManifest + "pp21", propertyPathManifest + "pp23", propertyPathManifest + "pp25", // Not supported: ZeroLengthPath
    propertyPathManifest + "pp28a", propertyPathManifest + "pp34", propertyPathManifest + "pp35", propertyPathManifest + "pp36", propertyPathManifest + "pp37",
    /* SERVICE not supported yet */
    serviceManifest + "service1", // no loading of the dataset
    serviceManifest + "service2", serviceManifest + "service3", serviceManifest + "service4a", serviceManifest + "service5", serviceManifest + "service6", serviceManifest + "service7",
    /* SUBQUERY */
    // Quad translated as a triple. TODO: fix it
    subqueryManifest + "subquery02", subqueryManifest + "subquery04", // EXISTS is not supported yet
    subqueryManifest + "subquery10", // ORDER BY IRI (for supported by the SI)
    subqueryManifest + "subquery11", // unbound variable: Var TODO: fix it
    subqueryManifest + "subquery12", subqueryManifest + "subquery13", // missing results (TODO: fix)
    subqueryManifest + "subquery14")


  var ontopProperties: Properties = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    ontopProperties = new Properties()
    ontopProperties.load(getClass.getClassLoader.getResourceAsStream("ontop-spark.properties"))
  }

  override def runQuery(query: Query, data: Model): SPARQLResult = {
    // distribute on Spark
    val triplesRDD = spark.sparkContext.parallelize(data.getGraph.find().toList.asScala)

    // do partitioning here
    val partitions: Map[RdfPartitionComplex, RDD[Row]] = RdfPartitionUtilsSpark.partitionGraph(triplesRDD, partitioner = RdfPartitionerComplex())

    // create the query engine
    val queryEngine = new OntopSPARQLEngine(spark, partitions)

    // run SPARQL query
    val (resultDF, resultDFRaw, queryRewrite) = queryEngine.executeDebug(query.toString)

    // produce result based on query type
    val result = if (query.isSelectType) { // SELECT
      // convert to bindings
      val bindings = if (queryRewrite.isDefined) {
        resultDF.show(false)
        toBindings(resultDFRaw, queryRewrite.get)
      } else {
        Array[Binding]()
      }

      // create the SPARQL result
      val model = ModelFactory.createDefaultModel()
      val vars = resultDF.schema.fieldNames.toList.asJava
      val resultsActual = new ResultSetStream(vars, model, bindings.toList.asJava.iterator())
      new SPARQLResult(resultsActual)
    } else if (query.isAskType) { // ASK
      // map DF entry to boolean
      val b = resultDFRaw.collect().head.getAs[Int](0) == 1
      new SPARQLResult(b)
    } else if (query.isConstructType) { // CONSTRUCT
      // convert to bindings
      val bindings = if (queryRewrite.isDefined) {
        resultDF.show(false)
        toBindings(resultDFRaw, queryRewrite.get)
      } else {
        Array[Binding]()
      }
      // convert to triples
      val triples = toTriples(bindings, queryRewrite.get)
      // create the SPARQL result
      val g = GraphFactory.createDefaultGraph()
      triples.foreach(g.add)
      val model = ModelFactory.createModelForGraph(g)
      new SPARQLResult(model)

    } else { // DESCRIBE todo
      fail("unsupported query type: DESCRIBE")
      null
    }
    result
  }

  /**
   * Convert an array of bindings to a set of triples.
   */
  private def toTriples(bindings: Array[Binding], queryRewrite: OntopQueryRewrite): Set[Triple] = {
    val constructTemplate = queryRewrite.inputQuery.asInstanceOf[ConstructQuery].getConstructTemplate

    val ex = constructTemplate.getExtension
    var extMap: Map[String, ValueExpr] = null
    if (ex != null) {
      extMap = ex.getElements.asScala.map(e => (e.getName, e.getExpr)).toMap
    }

    bindings.flatMap (binding => toTriples(binding, constructTemplate, extMap)).toSet
  }

  /**
   * Convert a single binding to a set of triples.
   */
  private def toTriples(binding: Binding, constructTemplate: ConstructTemplate, extMap: Map[String, ValueExpr]): mutable.Buffer[Triple] = {
    val l = constructTemplate.getProjectionElemList.asScala
    l.flatMap { peList =>
      val size = peList.getElements.size()

      var triples = scala.collection.mutable.Set[Triple]()

      for (i <- 0 until (size/3)) {

        val s = getConstant(peList.getElements.get(i * 3), binding, extMap)
        val p = getConstant(peList.getElements.get(i * 3 + 1), binding, extMap)
        val o = getConstant(peList.getElements.get(i * 3 + 2), binding, extMap)

        // A triple can only be constructed when none of bindings is missing
        if (s == null || p == null || o == null) {

        } else {
          triples += Triple.create(s, p, o)
        }
      }
      triples
    }
  }

  /**
   * Convert each node in a CONSTRUCT template to an RDF node.
   */
  private def getConstant(node: ProjectionElem, binding: Binding, extMap: Map[String, ValueExpr]): Node = {
    var constant: Node = null

    val node_name = node.getSourceName

    val ve: Option[ValueExpr] = if (extMap != null) extMap.get(node_name) else None

    // for constant terms in the template
    if (ve.isDefined && ve.get.isInstanceOf[ValueConstant]) {
      val vc = ve.get.asInstanceOf[ValueConstant]
      if (vc.getValue.isInstanceOf[IRI]) {
        constant = NodeFactory.createURI(vc.getValue.stringValue)
      } else if (vc.getValue.isInstanceOf[Literal]) {
        val lit = vc.getValue.asInstanceOf[Literal]
        val dt = TypeMapper.getInstance().getTypeByName(lit.getDatatype.toString)
        constant = NodeFactory.createLiteral(vc.getValue.stringValue, dt)
      } else {
        constant = NodeFactory.createBlankNode(vc.getValue.stringValue)
      }
    } else { // for variable bindings
      constant = binding.get(Var.alloc(node_name))
    }

    constant
  }

  override def toBinding(row: Row, metadata: AnyRef): Binding = {
    println(s"converting row: $row")
    val binding = BindingFactory.create()

    val queryRewrite = metadata.asInstanceOf[OntopQueryRewrite]

    val builder = ImmutableMap.builder[Variable, Constant]

    val it = queryRewrite.sqlSignature.iterator()
    for (i <- 0 until queryRewrite.sqlSignature.size()) {
      val variable = it.next()
      val value: String = if (row.get(i) != null) row.get(i).toString else null
      val sqlType = queryRewrite.sqlTypeMap.get(variable)
      builder.put(variable, new DBConstantImpl(value, sqlType))
    }
    val sub = queryRewrite.substitutionFactory.getSubstitution(builder.build)

    val composition = sub.composeWith(queryRewrite.sparqlVar2Term)
    val ontopBindings = queryRewrite.answerAtom.getArguments.asScala.map(v => {
      (v, evaluate(composition.apply(v)))
    })

    ontopBindings.foreach {
      case (v, Some(term)) => binding.add(Var.alloc(v.getName), toNode(term, queryRewrite.typeFactory))
      case _ =>
    }
    println(s"got binding: $binding")
    binding
  }

  private def toNode(constant: RDFConstant, typeFactory: TypeFactory): Node = {
    val termType = constant.getType
    if (termType.isA(typeFactory.getIRITermType)) {
      NodeFactory.createURI(constant.asInstanceOf[IRIConstant].getIRI.getIRIString)
    } else if (termType.isA(typeFactory.getAbstractRDFSLiteral)) {
      val lit = constant.asInstanceOf[RDFLiteralConstant]
      val litType = lit.getType
      val dt = TypeMapper.getInstance().getTypeByName(litType.getIRI.getIRIString)
      val lang = if (litType.getLanguageTag.isPresent) litType.getLanguageTag.get().getFullString else null
      NodeFactory.createLiteral(lit.getValue, lang, dt)
    } else {
      null.asInstanceOf[Node]
    }
  }

  private def evaluate(term: ImmutableTerm): Option[RDFConstant] = {
    val simplifiedTerm = term.simplify
    simplifiedTerm match {
      case constant: Constant =>
        if (constant.isInstanceOf[RDFConstant]) return Some(constant.asInstanceOf[RDFConstant])
        if (constant.isNull) return None
        if (constant.isInstanceOf[DBConstant]) throw new InvalidConstantTypeInResultException(constant +
          "is a DB constant. But a binding cannot have a DB constant as value")
        throw new InvalidConstantTypeInResultException("Unexpected constant type for " + constant)
      case _ =>
    }
    throw new InvalidTermAsResultException(simplifiedTerm)
  }

  class InvalidTermAsResultException(term: ImmutableTerm) extends OntopInternalBugException("Term " + term + " does not evaluate to a constant")
  class InvalidConstantTypeInResultException(message: String) extends OntopInternalBugException(message)

}
