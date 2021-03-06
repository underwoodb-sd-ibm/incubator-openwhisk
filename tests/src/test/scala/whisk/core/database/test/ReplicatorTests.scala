/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database.test

import java.io.File
import java.time.Instant

import scala.concurrent.duration._
import scala.language.implicitConversions

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.StatusCodes
import common.StreamLogging
import common.TestUtils
import common.WaitFor
import common.WhiskProperties
import common.WskActorSystem
import spray.json._
import spray.json.DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class ReplicatorTests extends FlatSpec
    with Matchers
    with ScalaFutures
    with WskActorSystem
    with WaitFor
    with StreamLogging
    with DatabaseScriptTestUtils {

    val testDbPrefix = s"replicatortest_${dbPrefix}"

    val replicatorClient = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort.toInt, dbUsername, dbPassword, "_replicator")

    val replicator = WhiskProperties.getFileRelativeToWhiskHome("tools/db/replicateDbs.py").getAbsolutePath
    val designDocPath = WhiskProperties.getFileRelativeToWhiskHome("ansible/files/filter_design_document.json").getAbsolutePath

    implicit def toDuration(dur: FiniteDuration) = java.time.Duration.ofMillis(dur.toMillis)
    def toEpochSeconds(i: Instant) = i.toEpochMilli / 1000

    /** Removes a document from the _replicator-database */
    def removeReplicationDoc(id: String) = {
        println(s"Removing replication doc: $id")
        val response = replicatorClient.getDoc(id).futureValue
        val rev = response.right.toOption.map(_.fields("_rev").convertTo[String])
        rev.map(replicatorClient.deleteDoc(id, _).futureValue)
    }

    /** Runs the replicator script to replicate databases */
    def runReplicator(sourceDbUrl: String, targetDbUrl: String, dbPrefix: String, expires: FiniteDuration, continuous: Boolean = false, exclude: List[String] = List()) = {
        println(s"Running replicator: $sourceDbUrl, $targetDbUrl, $dbPrefix, $expires, $continuous, $exclude")

        val continuousFlag = if (continuous) Some("--continuous") else None
        val excludeFlag = Seq(exclude.mkString(",")).filter(_.nonEmpty).flatMap(ex => Seq("--exclude", ex))
        val cmd = Seq(python, replicator, "--sourceDbUrl", sourceDbUrl, "--targetDbUrl", targetDbUrl, "replicate", "--dbPrefix", dbPrefix, "--expires", expires.toSeconds.toString) ++ continuousFlag ++ excludeFlag
        val rr = TestUtils.runCmd(0, new File("."), cmd: _*)

        val Seq(created, deletedDoc, deleted) = Seq("create backup: ", "deleting backup document: ", "deleting backup: ").map { prefix =>
            rr.stdout.lines.collect {
                case line if line.startsWith(prefix) => line.replace(prefix, "")
            }.toList
        }

        println(s"Created: $created")
        println(s"DeletedDocs: $deletedDoc")
        println(s"Deleted: $deleted")

        (created, deletedDoc, deleted)
    }

    /** Runs the replicator script to replay databases */
    def runReplay(sourceDbUrl: String, targetDbUrl: String, dbPrefix: String) = {
        println(s"Running replay: $sourceDbUrl, $targetDbUrl, $dbPrefix")
        val rr = TestUtils.runCmd(0, new File("."), WhiskProperties.python, WhiskProperties.getFileRelativeToWhiskHome("tools/db/replicateDbs.py").getAbsolutePath, "--sourceDbUrl", sourceDbUrl, "--targetDbUrl", targetDbUrl, "replay", "--dbPrefix", dbPrefix)

        val line = """([\w-]+) -> ([\w-]+) \(([\w-]+)\)""".r.unanchored
        val replays = rr.stdout.lines.collect {
            case line(backup, target, id) => (backup, target, id)
        }.toList

        println(s"Replays created: $replays")

        replays
    }

    /** Wait for a replication to finish */
    def waitForReplication(dbName: String) = {
        waitfor(() => {
            val replicatorDoc = replicatorClient.getDoc(dbName).futureValue
            replicatorDoc shouldBe 'right

            val state = replicatorDoc.right.get.fields.get("_replication_state")
            println(s"Waiting for replication, state: $state")

            state.contains("completed".toJson)
        }, totalWait = 2.minutes)
    }

    /** Compares to databases to full equality */
    def compareDatabases(sourceDb: String, targetDb: String, filterUsed: Boolean) = {
        val originalDocs = getAllDocs(sourceDb)
        val replicatedDocs = getAllDocs(targetDb)

        if (!filterUsed) {
            replicatedDocs shouldBe originalDocs
        } else {
            val filteredReplicatedDocs = replicatedDocs.fields("rows").convertTo[List[JsObject]]
            val filteredOriginalDocs = originalDocs.fields("rows").convertTo[List[JsObject]].filterNot(_.fields("id").convertTo[String].startsWith("_design/"))

            filteredReplicatedDocs shouldBe filteredOriginalDocs
        }
    }

    behavior of "Database replication script"

    it should "replicate a database (snapshot)" in {
        // Create a database to backup
        val dbName = testDbPrefix + "database_for_single_replication"
        val client = createDatabase(dbName, Some(designDocPath))

        println(s"Creating testdocument")
        val testDocument = JsObject("testKey" -> "testValue".toJson)
        client.putDoc("testId", testDocument).futureValue

        // Trigger replication and verify the created databases have the correct format
        val (createdBackupDbs, _, _) = runReplicator(dbUrl, dbUrl, testDbPrefix, 10.minutes)
        createdBackupDbs should have size 1
        val backupDbName = createdBackupDbs.head
        backupDbName should fullyMatch regex s"backup_\\d+_$dbName"

        // Wait for the replication to finish
        waitForReplication(backupDbName)

        // Verify the replicated database is equal to the original database
        compareDatabases(dbName, backupDbName, true)

        // Remove all created databases
        createdBackupDbs.foreach(removeDatabase(_))
        createdBackupDbs.foreach(removeReplicationDoc(_))
        removeDatabase(dbName)
    }

    it should "do not replicate a database that is excluded" in {
        // Create a database to backup
        val dbNameToBackup = testDbPrefix + "database_for_single_replication_with_exclude"
        val nExClient = createDatabase(dbNameToBackup, Some(designDocPath))

        val excludedName = "some_excluded_name"
        val exClient = createDatabase(testDbPrefix + excludedName, Some(designDocPath))

        // Trigger replication and verify the created databases have the correct format
        val (createdBackupDbs, _, _) = runReplicator(dbUrl, dbUrl, testDbPrefix, 10.minutes, exclude = List(excludedName))
        createdBackupDbs should have size 1
        val backupDbName = createdBackupDbs.head
        backupDbName should fullyMatch regex s"backup_\\d+_$dbNameToBackup"

        // Wait for the replication to finish
        waitForReplication(backupDbName)

        // Remove all created databases
        createdBackupDbs.foreach(removeDatabase(_))
        createdBackupDbs.foreach(removeReplicationDoc(_))
        removeDatabase(dbNameToBackup)
        removeDatabase(testDbPrefix + excludedName)
    }

    it should "replicate a database (snapshot) even if the filter is not available" in {
        // Create a db to backup
        val dbName = testDbPrefix + "database_for_snapshout_without_filter"
        val client = createDatabase(dbName, None)

        println("Creating testdocuments")
        val testDocuments = Seq(
            JsObject(
                "testKey" -> "testValue".toJson,
                "_id" -> "doc1".toJson),
            JsObject("testKey" -> "testValue".toJson,
                "_id" -> "_design/doc1".toJson))
        val documents = testDocuments.map { doc =>
            val res = client.putDoc(doc.fields("_id").convertTo[String], doc).futureValue
            res shouldBe 'right
            res.right.get
        }

        // Trigger replication and verify the created databases have the correct format
        val (createdBackupDbs, _, _) = runReplicator(dbUrl, dbUrl, testDbPrefix, 10.minutes)
        createdBackupDbs should have size 1
        val backupDbName = createdBackupDbs.head
        backupDbName should fullyMatch regex s"backup_\\d+_$dbName"

        // Wait for the replication to finish
        waitForReplication(backupDbName)

        // Verify the replicated database is equal to the original database
        compareDatabases(dbName, backupDbName, false)

        // Remove all created databases
        createdBackupDbs.foreach(removeDatabase(_))
        createdBackupDbs.foreach(removeReplicationDoc(_))
        removeDatabase(dbName)
    }

    it should "replicate a database (snapshot) and deleted documents and design documents should not be in the snapshot" in {
        // Create a database to backup
        val dbName = testDbPrefix + "database_for_single_replication_design_and_deleted_docs"
        val client = createDatabase(dbName, Some(designDocPath))

        println(s"Creating testdocument")
        val testDocuments = Seq(
            JsObject(
                "testKey" -> "testValue".toJson,
                "_id" -> "doc1".toJson),
            JsObject("testKey" -> "testValue".toJson,
                "_id" -> "doc2".toJson),
            JsObject("testKey" -> "testValue".toJson,
                "_id" -> "_design/doc1".toJson))
        val documents = testDocuments.map { doc =>
            val res = client.putDoc(doc.fields("_id").convertTo[String], doc).futureValue
            res shouldBe 'right
            res.right.get
        }

        // Delete second document again
        val indexOfDocumentToDelete = 1
        val idOfDeletedDocument = documents(indexOfDocumentToDelete).fields("id").convertTo[String]
        client.deleteDoc(idOfDeletedDocument, documents(indexOfDocumentToDelete).fields("rev").convertTo[String])

        // Trigger replication and verify the created databases have the correct format
        val (createdBackupDbs, _, _) = runReplicator(dbUrl, dbUrl, testDbPrefix, 10.minutes)
        createdBackupDbs should have size 1
        val backupDbName = createdBackupDbs.head
        backupDbName should fullyMatch regex s"backup_\\d+_$dbName"

        // Wait for the replication to finish
        waitForReplication(backupDbName)

        // Verify the replicated database is equal to the original database
        compareDatabases(dbName, backupDbName, true)

        // Check that deleted doc has not been copied to snapshot
        val snapshotClient = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort.toInt, dbUsername, dbPassword, backupDbName)
        val snapshotResponse = snapshotClient.getAllDocs(keys = Some(List(idOfDeletedDocument))).futureValue
        snapshotResponse shouldBe 'right
        val results = snapshotResponse.right.get.fields("rows").convertTo[List[JsObject]]
        results should have size 1
        // If deleted doc would be in db, the document id and rev would have been returned
        results(0) shouldBe JsObject("key" -> idOfDeletedDocument.toJson, "error" -> "not_found".toJson)

        // Remove all created databases
        createdBackupDbs.foreach(removeDatabase(_))
        createdBackupDbs.foreach(removeReplicationDoc(_))
        removeDatabase(dbName)
    }

    it should "continuously update a database" in {
        // Create a database to backup
        val dbName = testDbPrefix + "database_for_continous_replication"
        val client = createDatabase(dbName, Some(designDocPath))

        // Trigger replication and verify the created databases have the correct format
        val (createdBackupDbs, _, _) = runReplicator(dbUrl, dbUrl, testDbPrefix, 10.minutes, true)
        createdBackupDbs should have size 1
        val backupDbName = createdBackupDbs.head
        backupDbName shouldBe s"continuous_$dbName"

        // Wait for the replicated database to appear
        val backupClient = waitForDatabase(backupDbName)

        // Create a document in the old database
        println(s"Creating testdocument")
        val docId = "testId"
        val testDocument = JsObject("testKey" -> "testValue".toJson)
        client.putDoc(docId, testDocument).futureValue

        // Wait for the document to appear
        waitForDocument(backupClient, docId)

        // Verify the replicated database is equal to the original database
        compareDatabases(backupDbName, dbName, false)

        // Stop the replication
        val replication = replicatorClient.getDoc(backupDbName).futureValue
        replication shouldBe 'right
        val replicationDoc = replication.right.get
        replicatorClient.deleteDoc(replicationDoc.fields("_id").convertTo[String], replicationDoc.fields("_rev").convertTo[String])

        // Remove all created databases
        createdBackupDbs.foreach(removeDatabase(_))
        createdBackupDbs.foreach(removeReplicationDoc(_))
        removeDatabase(dbName)
    }

    it should "remove outdated databases and replicationDocs" in {
        val now = Instant.now()
        val expires = 10.minutes

        println(s"Now is: ${toEpochSeconds(now)}")

        // Create a database that is already expired
        val expired = now.minus(expires + 5.minutes)
        val expiredName = s"backup_${toEpochSeconds(expired)}_${testDbPrefix}expired_backup"
        val expiredClient = createDatabase(expiredName, Some(designDocPath))
        replicatorClient.putDoc(expiredName, JsObject("source" -> "".toJson, "target" -> "".toJson)).futureValue

        // Create a database that is not yet expired
        val notExpired = now.plus(expires - 5.minutes)
        val notExpiredName = s"backup_${toEpochSeconds(notExpired)}_${testDbPrefix}notexpired_backup"
        val notExpiredClient = createDatabase(notExpiredName, Some(designDocPath))
        replicatorClient.putDoc(notExpiredName, JsObject("source" -> "".toJson, "target" -> "".toJson)).futureValue

        // Trigger replication and verify the expired database is deleted while the unexpired one is kept
        val (createdDatabases, deletedReplicationDocs, deletedDatabases) = runReplicator(dbUrl, dbUrl, testDbPrefix, expires)
        deletedReplicationDocs should (contain(expiredName) and not contain (notExpiredName))
        deletedDatabases should (contain(expiredName) and not contain (notExpiredName))

        expiredClient.getAllDocs().futureValue shouldBe Left(StatusCodes.NotFound)
        notExpiredClient.getAllDocs().futureValue shouldBe 'right

        // Cleanup backup database
        createdDatabases.foreach(removeDatabase(_))
        createdDatabases.foreach(removeReplicationDoc(_))
        removeReplicationDoc(notExpiredName)
        removeDatabase(notExpiredName)
    }

    it should "not remove outdated databases with other prefix" in {
        val now = Instant.now()
        val expires = 10.minutes

        println(s"Now is: ${toEpochSeconds(now)}")

        val expired = now.minus(expires + 5.minutes)

        // Create a database that is expired with correct prefix
        val correctPrefixName = s"backup_${toEpochSeconds(expired)}_${testDbPrefix}expired_backup_correct_prefix"
        val correctPrefixClient = createDatabase(correctPrefixName, Some(designDocPath))
        replicatorClient.putDoc(correctPrefixName, JsObject("source" -> "".toJson, "target" -> "".toJson)).futureValue

        // Create a database that is expired with wrong prefix
        val wrongPrefix = s"replicatortest_wrongprefix_${dbPrefix}"
        val wrongPrefixName = s"backup_${toEpochSeconds(expired)}_${wrongPrefix}expired_backup_wrong_prefix"
        val wrongPrefixClient = createDatabase(wrongPrefixName, Some(designDocPath))
        replicatorClient.putDoc(wrongPrefixName, JsObject("source" -> "".toJson, "target" -> "".toJson)).futureValue

        // Trigger replication and verify the expired database with correct prefix is deleted while the db with the wrong prefix is kept
        val (createdDatabases, deletedReplicationDocs, deletedDatabases) = runReplicator(dbUrl, dbUrl, testDbPrefix, expires)
        deletedReplicationDocs should (contain(correctPrefixName) and not contain (wrongPrefixName))
        deletedDatabases should (contain(correctPrefixName) and not contain (wrongPrefixName))

        correctPrefixClient.getAllDocs().futureValue shouldBe Left(StatusCodes.NotFound)
        wrongPrefixClient.getAllDocs().futureValue shouldBe 'right

        // Cleanup backup database
        createdDatabases.foreach(removeDatabase(_))
        createdDatabases.foreach(removeReplicationDoc(_))
        removeReplicationDoc(wrongPrefixName)
        removeDatabase(wrongPrefixName)
    }

    it should "replay a database" in {
        val now = Instant.now()
        val dbName = testDbPrefix + "database_to_be_restored"
        val backupPrefix = s"backup_${toEpochSeconds(now)}_"
        val backupDbName = backupPrefix + dbName

        // Create a database that looks like a backup
        val backupClient = createDatabase(backupDbName, Some(designDocPath))
        println(s"Creating testdocument")
        backupClient.putDoc("testId", JsObject("testKey" -> "testValue".toJson)).futureValue

        // Run the replay script
        val (_, _, replicationId) = runReplay(dbUrl, dbUrl, backupPrefix).head

        // Wait for the replication to finish
        waitForReplication(replicationId)

        // Verify the replicated database is equal to the original database
        compareDatabases(backupDbName, dbName, false)

        // Cleanup databases
        removeDatabase(backupDbName)
        removeDatabase(dbName)
    }
}
