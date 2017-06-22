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

package apigw.healthtests

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import com.jayway.restassured.RestAssured

import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.Wsk
import common.WskAdmin
import common.WskProps
import common.WskPropsV2
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import system.rest.RestUtil

/**
 * Basic tests of the download link for Go CLI binaries
 */
@RunWith(classOf[JUnitRunner])
class ApiGwEndToEndTests
    extends FlatSpec
    with Matchers
    with RestUtil
    with TestHelpers
    with WskTestHelpers
    with BeforeAndAfterAll {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val (cliuser, clinamespace) = WskAdmin.getUser(wskprops.authKey)

    // Custom CLI properties file
    val cliWskPropsFile = File.createTempFile("wskprops", ".tmp")

    /*
     * Create a CLI properties file for use by the tests
     */
    override def beforeAll() = {
        cliWskPropsFile.deleteOnExit()
        val wskprops = WskPropsV2(token = "SOME TOKEN")
        wskprops.writeFile(cliWskPropsFile)
        println(s"wsk temporary props file created here: ${cliWskPropsFile.getCanonicalPath()}")
    }

    behavior of "Wsk api-experimental"

    it should "return a list of alphabetized api-experimental" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>

        val actionName1 = "actionName1"
        val actionName2 = "actionName2"
        val actionName3 = "actionName3"
        val base1 = "/BaseTestPath1"
        val base2 = "/BaseTestPath2"
        val base3 = "/BaseTestPath3"

        try {
            //Create Actions for apiexperimentals
            val file = TestUtils.getTestActionFilename(s"echo-web-http.js")
            println("Create Action: " + actionName1)
            assetHelper.withCleaner(wsk.action, actionName1) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            println("Create Action: " + actionName2)
            assetHelper.withCleaner(wsk.action, actionName2) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            println("Create Action: " + actionName3)
            assetHelper.withCleaner(wsk.action, actionName3) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            //Create apiexperimentals
            println("Create api-experimental: Base Path " + base2)
            wsk.apiexperimental.create(
              basepath = Some(base2),
              relpath = Some("/relPath1"),
              operation = Some("get"),
              action = Some(actionName2)
            )
            println("Create api-experimental: Base Path " + base1)
            wsk.apiexperimental.create(
              basepath = Some(base1),
              relpath = Some("/relPath2"),
              operation = Some("delete"),
              action = Some(actionName1)
            )
            println("Create api-experimental: Base Path " + base3)
            wsk.apiexperimental.create(
              basepath = Some(base3),
              relpath = Some("/relPath3"),
              operation = Some("head"),
              action = Some(actionName3)
            )
            val original = wsk.apiexperimental.list().stdout
            val originalFull = wsk.apiexperimental.list(full = Some(true)).stdout
            val scalaSorted = List(base1 + "/", base2 + "/", base3 + "/")
            val regex = "/BaseTestPath[1-3]/".r
            val list  = (regex.findAllMatchIn(original)).toList
            val listFull  = (regex.findAllMatchIn(originalFull)).toList
            scalaSorted.toString shouldEqual list.toString
            scalaSorted.toString shouldEqual listFull.toString
        } finally {
            //Clean up apiexperimentals
            println("Delete api-experimental: Base Path " + base1)
            wsk.apiexperimental.delete(base1, expectedExitCode = DONTCARE_EXIT)
            println("Delete api-experimental: Base Path " + base2)
            wsk.apiexperimental.delete(base2, expectedExitCode = DONTCARE_EXIT)
            println("Delete api-experimental: Base Path " + base3)
            wsk.apiexperimental.delete(base3, expectedExitCode = DONTCARE_EXIT)
        }
    }

    it should "return a list of alphabetized api-experimental by action name" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>

        val actionName1 = "actionName1"
        val actionName2 = "actionName2"
        val actionName3 = "actionName3"
        val base1 = "/BaseTestPath1"
        val base2 = "/BaseTestPath2"
        val base3 = "/BaseTestPath3"

        try {
            //Create Actions for api-experimentals
            val file = TestUtils.getTestActionFilename(s"echo-web-http.js")
            println("Create Action: " + actionName1)
            assetHelper.withCleaner(wsk.action, actionName1) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            println("Create Action: " + actionName2)
            assetHelper.withCleaner(wsk.action, actionName2) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            println("Create Action: " + actionName3)
            assetHelper.withCleaner(wsk.action, actionName3) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            //Create api-experimentals
            println("Create api-experimental: Base Path " + base2)
            wsk.apiexperimental.create(
              basepath = Some(base2),
              relpath = Some("/relPath1"),
              operation = Some("get"),
              action = Some(actionName2)
            )
            println("Create apiexperimental: Base Path " + base1)
            wsk.apiexperimental.create(
              basepath = Some(base1),
              relpath = Some("/relPath2"),
              operation = Some("delete"),
              action = Some(actionName1)
            )
            println("Create apiexperimental: Base Path " + base3)
            wsk.apiexperimental.create(
              basepath = Some(base3),
              relpath = Some("/relPath3"),
              operation = Some("head"),
              action = Some(actionName3)
            )
            val original = wsk.apiexperimental.list(sortAction = Some(true)).stdout
            val originalFull = wsk.apiexperimental.list(full = Some(true), sortAction = Some(true)).stdout
            val scalaSorted = List(actionName1, actionName2, actionName3)
            val regex = "actionName[1-3]".r
            val list  = (regex.findAllMatchIn(original)).toList
            val listFull  = (regex.findAllMatchIn(originalFull)).toList
            scalaSorted.toString shouldEqual list.toString
            scalaSorted.toString shouldEqual listFull.toString
        } finally {
            //Clean up apiexperimentals
            println("Delete apiexperimental: Base Path " + base1)
            wsk.apiexperimental.delete(base1, expectedExitCode = DONTCARE_EXIT)
            println("Delete apiexperimental: Base Path " + base2)
            wsk.apiexperimental.delete(base2, expectedExitCode = DONTCARE_EXIT)
            println("Delete apiexperimental: Base Path " + base3)
            wsk.apiexperimental.delete(base3, expectedExitCode = DONTCARE_EXIT)
        }
    }

    it should s"create an API and successfully invoke that API" in {
        val testName = "APIGWe_HEALTHTEST1"
        val testbasepath = "/" + testName + "_bp"
        val testrelpath = "/path"
        val testurlop = "get"
        val testapiname = testName + " API Name"
        val actionName = testName + "_echo"
        val urlqueryparam = "name"
        val urlqueryvalue = "test"

        try {
            println("cli user: " + cliuser + "; cli namespace: " + clinamespace)

            // Create the action for the API
            val file = TestUtils.getTestActionFilename(s"echo.js")
            wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = SUCCESS_EXIT)

            // Create the API
            var rr = wsk.apiexperimental.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
            rr.stdout should include("ok: created API")
            val apiurl = rr.stdout.split("\n")(1)
            println(s"apiurl: '${apiurl}'")

            // Validate the API was successfully created
            // List result will look like:
            // ok: APIs
            // Action                            Verb             API Name  URL
            // /_//whisk.system/utils/echo          get  APIGW_HEALTHTEST1 API Name  http://172.17.0.1:9001/api/ab9082cd-ea8e-465a-8a65-b491725cc4ef/APIGW_HEALTHTEST1_bp/path
            rr = wsk.apiexperimental.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
            rr.stdout should include("ok: APIs")
            rr.stdout should include regex (s"${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
            rr.stdout should include(testbasepath + testrelpath)

            // Recreate the API using a JSON swagger file
            rr = wsk.apiexperimental.get(basepathOrApiName = Some(testbasepath))
            val swaggerfile = File.createTempFile("api", ".json")
            swaggerfile.deleteOnExit()
            val bw = new BufferedWriter(new FileWriter(swaggerfile))
            bw.write(rr.stdout)
            bw.close()

            // Delete API to that it can be recreated again using the generated swagger file
            val deleteApiResult = wsk.apiexperimental.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)

            // Create the API again, but use the swagger file this time
            rr = wsk.apiexperimental.create(swagger = Some(swaggerfile.getAbsolutePath()))
            rr.stdout should include("ok: created API")
            val swaggerapiurl = rr.stdout.split("\n")(1)
            println(s"apiurl: '${swaggerapiurl}'")

            // Call the API URL and validate the results
            val response = whisk.utils.retry({
                val response = RestAssured.given().config(sslconfig).get(s"$swaggerapiurl?$urlqueryparam=$urlqueryvalue")
                response.statusCode should be(200)
                response
            }, 5, Some(1.second))
            val responseString = response.body.asString
            println("URL invocation response: " + responseString)
            responseString.parseJson.asJsObject.fields(urlqueryparam).convertTo[String] should be(urlqueryvalue)

        } finally {
            println("Deleting action: " + actionName)
            val finallydeleteActionResult = wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
            println("Deleting API: " + testbasepath)
            val finallydeleteApiResult = wsk.apiexperimental.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        }
    }

    behavior of "Wsk api"

    it should "return a list of alphabetized api" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>

        val actionName1 = "actionName1"
        val actionName2 = "actionName2"
        val actionName3 = "actionName3"
        val base1 = "/BaseTestPath1"
        val base2 = "/BaseTestPath2"
        val base3 = "/BaseTestPath3"

        try {
            //Create Actions for Apis
            val file = TestUtils.getTestActionFilename(s"echo-web-http.js")
            println("Create Action: " + actionName1)
            assetHelper.withCleaner(wsk.action, actionName1) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            println("Create Action: " + actionName2)
            assetHelper.withCleaner(wsk.action, actionName2) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            println("Create Action: " + actionName3)
            assetHelper.withCleaner(wsk.action, actionName3) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            //Create Apis
            println("Create API: Base Path " + base2)
            wsk.api.create(
              basepath = Some(base2),
              relpath = Some("/relPath1"),
              operation = Some("get"),
              action = Some(actionName2),
              cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            println("Create API: Base Path " + base1)
            wsk.api.create(
              basepath = Some(base1),
              relpath = Some("/relPath2"),
              operation = Some("delete"),
              action = Some(actionName1),
              cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            println("Create API: Base Path " + base3)
            wsk.api.create(
              basepath = Some(base3),
              relpath = Some("/relPath3"),
              operation = Some("head"),
              action = Some(actionName3),
              cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            val original = wsk.api.list(cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())).stdout
            val originalFull = wsk.api.list(full = Some(true), cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())).stdout
            val scalaSorted = List(base1 + "/", base2 + "/", base3 + "/")
            val regex = "/BaseTestPath[1-3]/".r
            val list  = (regex.findAllMatchIn(original)).toList
            val listFull = (regex.findAllMatchIn(originalFull)).toList
            scalaSorted.toString shouldEqual list.toString
            scalaSorted.toString shouldEqual listFull.toString
        } finally {
            //Clean up Apis
            println("Delete API: Base Path " + base1)
            wsk.api.delete(base1, expectedExitCode = DONTCARE_EXIT, cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
            println("Delete API: Base Path " + base2)
            wsk.api.delete(base2, expectedExitCode = DONTCARE_EXIT, cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
            println("Delete API: Base Path " + base3)
            wsk.api.delete(base3, expectedExitCode = DONTCARE_EXIT, cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
        }
    }

    it should "return a list of alphabetized api by action name" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>

        val actionName1 = "actionName1"
        val actionName2 = "actionName2"
        val actionName3 = "actionName3"
        val base1 = "/BaseTestPath1"
        val base2 = "/BaseTestPath2"
        val base3 = "/BaseTestPath3"

        try {
            //Create Actions for Apis
            val file = TestUtils.getTestActionFilename(s"echo-web-http.js")
            println("Create Action: " + actionName1)
            assetHelper.withCleaner(wsk.action, actionName1) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            println("Create Action: " + actionName2)
            assetHelper.withCleaner(wsk.action, actionName2) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            println("Create Action: " + actionName3)
            assetHelper.withCleaner(wsk.action, actionName3) {
                (action, name) => action.create(name, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
            }
            //Create Apis
            println("Create API: Base Path " + base2)
            wsk.api.create(
              basepath = Some(base2),
              relpath = Some("/relPath1"),
              operation = Some("get"),
              action = Some(actionName2),
              cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            println("Create API: Base Path " + base1)
            wsk.api.create(
              basepath = Some(base1),
              relpath = Some("/relPath2"),
              operation = Some("delete"),
              action = Some(actionName1),
              cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            println("Create API: Base Path " + base3)
            wsk.api.create(
              basepath = Some(base3),
              relpath = Some("/relPath3"),
              operation = Some("head"),
              action = Some(actionName3),
              cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            val original = wsk.api.list(sortAction = Some(true),
                cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())).stdout
            val originalFull = wsk.api.list(full = Some(true), sortAction = Some(true),
                cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())).stdout
            val scalaSorted = List(actionName1, actionName2, actionName3)
            val regex = "actionName[1-3]".r
            val list  = (regex.findAllMatchIn(original)).toList
            val listFull  = (regex.findAllMatchIn(originalFull)).toList
            scalaSorted.toString shouldEqual list.toString
            scalaSorted.toString shouldEqual listFull.toString
        } finally {
            //Clean up Apis
            println("Delete API: Base Path " + base1)
            wsk.api.delete(base1, expectedExitCode = DONTCARE_EXIT, cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
            println("Delete API: Base Path " + base2)
            wsk.api.delete(base2, expectedExitCode = DONTCARE_EXIT, cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
            println("Delete API: Base Path " + base3)
            wsk.api.delete(base3, expectedExitCode = DONTCARE_EXIT, cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
        }
    }


    it should s"create an API and successfully invoke that API" in {
        val testName = "APIGW_HEALTHTEST1"
        val testbasepath = "/" + testName + "_bp"
        val testrelpath = "/path"
        val testurlop = "get"
        val testapiname = testName + " API Name"
        val actionName = testName + "_echo"
        val urlqueryparam = "name"
        val urlqueryvalue = "test"

        try {
            println("cli user: " + cliuser + "; cli namespace: " + clinamespace)

            // Create the action for the API.  It must be a "web-action" action.
            val file = TestUtils.getTestActionFilename(s"echo-web-http.js")
            wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, annotations = Map("web-export" -> true.toJson))

            // Create the API
            var rr = wsk.api.create(
                basepath = Some(testbasepath),
                relpath = Some(testrelpath),
                operation = Some(testurlop),
                action = Some(actionName),
                apiname = Some(testapiname),
                responsetype = Some("http"),
                cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            rr.stdout should include("ok: created API")
            val apiurl = rr.stdout.split("\n")(1)
            println(s"apiurl: '${apiurl}'")

            // Validate the API was successfully created
            // List result will look like:
            // ok: APIs
            // Action                            Verb             API Name  URL
            // /_//whisk.system/utils/echo          get  APIGW_HEALTHTEST1 API Name  http://172.17.0.1:9001/api/ab9082cd-ea8e-465a-8a65-b491725cc4ef/APIGW_HEALTHTEST1_bp/path
            rr = wsk.api.list(
                basepathOrApiName = Some(testbasepath),
                relpath = Some(testrelpath),
                operation = Some(testurlop),
                cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            rr.stdout should include("ok: APIs")
            rr.stdout should include regex (s"${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
            rr.stdout should include(testbasepath + testrelpath)

            // Recreate the API using a JSON swagger file
            rr = wsk.api.get(
                basepathOrApiName = Some(testbasepath),
                cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            val swaggerfile = File.createTempFile("api", ".json")
            swaggerfile.deleteOnExit()
            val bw = new BufferedWriter(new FileWriter(swaggerfile))
            bw.write(rr.stdout)
            bw.close()

            // Delete API to that it can be recreated again using the generated swagger file
            val deleteApiResult = wsk.api.delete(
                basepathOrApiName = testbasepath,
                expectedExitCode = DONTCARE_EXIT,
                cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )

            // Create the API again, but use the swagger file this time
            rr = wsk.api.create(
                swagger = Some(swaggerfile.getAbsolutePath()),
                cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
            rr.stdout should include("ok: created API")
            val swaggerapiurl = rr.stdout.split("\n")(1)
            println(s"apiurl: '${swaggerapiurl}'")

            // Call the API URL and validate the results
            val response = whisk.utils.retry({
                val response = RestAssured.given().config(sslconfig).get(s"$swaggerapiurl?$urlqueryparam=$urlqueryvalue")
                response.statusCode should be(200)
                response
            }, 5, Some(1.second))
            val responseString = response.body.asString
            println("URL invocation response: " + responseString)
            responseString.parseJson.asJsObject.fields(urlqueryparam).convertTo[String] should be(urlqueryvalue)

        } finally {
            println("Deleting action: " + actionName)
            val finallydeleteActionResult = wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
            println("Deleting API: " + testbasepath)
            val finallydeleteApiResult = wsk.api.delete(
                basepathOrApiName = testbasepath,
                expectedExitCode = DONTCARE_EXIT,
                cliCfgFile = Some(cliWskPropsFile.getCanonicalPath())
            )
        }
    }
}
