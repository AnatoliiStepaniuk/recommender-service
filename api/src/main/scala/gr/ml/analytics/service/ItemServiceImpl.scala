package gr.ml.analytics.service

import com.datastax.driver.core.Row
import com.typesafe.scalalogging.LazyLogging
import gr.ml.analytics.cassandra.{CassandraCache, InputDatabase}
import gr.ml.analytics.domain.{Item, Schema}

import scala.concurrent.Future
import scala.util.parsing.json.JSONObject
import scala.concurrent.ExecutionContext.Implicits.global

class ItemServiceImpl(val inputDatabase: InputDatabase, val cassandraCache: CassandraCache) extends ItemService with LazyLogging {

  private lazy val notRatedItemModel = inputDatabase.notRatedItemsModel

  /**
    * @inheritdoc
    */
  override def get(schemaId: Int, itemId: Int): Future[Option[Item]] = {
    val schemaFuture = inputDatabase.schemasModel.getOne(schemaId)

    schemaFuture.map {
      case Some(schema) =>
        val schemaMap: Map[String, Any] = schema.jsonSchema
        val (idName, idType) = Util.extractIdMetadata(schemaMap)
        val featureColumnsInfo: List[Map[String, String]] = Util.extractFeaturesMetadata(schemaMap)

        // retrieve content in json format
        val tableName = Util.itemsTableName(schemaId)
        val query = s"SELECT JSON * FROM ${inputDatabase.ratingModel.keySpace}.$tableName WHERE $idName = $itemId"
        val res = inputDatabase.connector.session.execute(query).one()

        if (res != null) {
          val item: Item = Util.convertJson(res.get("[json]", classOf[String]))
          Some(item)
        } else {
          None
        }

      case None => None
    }
  }

  /**
    * @inheritdoc
    */
  override def get(schemaId: Int, itemIds: List[Int]): Future[List[Option[Item]]] = {
    Future.sequence(itemIds.map(itemId => get(schemaId, itemId)))
  }

  /**
    * @inheritdoc
    */
  override def save(schemaId: Int, item: Item): Future[Option[Int]] = {
    val schemaFuture = inputDatabase.schemasModel.getOne(schemaId)

    schemaFuture.map {
      case Some(schema: Schema) =>
        val schemaMap: Map[String, Any] = schema.jsonSchema
        val (idName, idType) = Util.extractIdMetadata(schemaMap)
        val featureColumnsInfo: List[Map[String, String]] = Util.extractFeaturesMetadata(schemaMap)

        // TODO implement proper mechanism to escape characters which have special meaning for Cassandra
        val json = JSONObject(item).toString().replace("'", "")
        val tableName = Util.itemsTableName(schemaId)
        val tableNameDense = Util.itemsTableName(schemaId) + "_dense"
        val keyspace = inputDatabase.ratingModel.keySpace
        val query = s"INSERT INTO $keyspace.$tableName JSON '$json'"
        val res = inputDatabase.connector.session.execute(query).wasApplied()

        // inserting into dense items table
        val allowedTypes = Set("double", "float")
        val idColumnName = schemaMap("id").asInstanceOf[Map[String, String]]("name")
        val itemId = item.get(idColumnName).get
        val featureColumnNames = schemaMap("features").asInstanceOf[List[Map[String, String]]]
          .filter((colDescription: Map[String, Any]) => allowedTypes.contains(colDescription("type").asInstanceOf[String].toLowerCase))
          .map(colDescription => colDescription("name"))

        val featureValues: List[Int] = featureColumnNames
          .map(name => item.get(name))
          .map(some => some.get.toString.toInt)
          .toArray.toList

        val featuresString = "[" + featureValues.toArray.mkString(", ") + "]"
        val insertDenseItemQuery = s"INSERT INTO $keyspace.$tableNameDense (itemid, features) values ($itemId, $featuresString)";
        inputDatabase.connector.session.execute(insertDenseItemQuery)

        logger.info(s"Creating item: '$query' Result: $res")

        cassandraCache.invalidateItemIDs()

        val userIds = cassandraCache.getAllUserIDs()
        userIds.foreach(userId => notRatedItemModel.addNotRatedItem(userId, itemId.toString.toInt))// TODO int type is hardcoded...

        Some(item(idName.toLowerCase()).asInstanceOf[Integer].intValue())
      case None =>
        None
    }
  }
}
