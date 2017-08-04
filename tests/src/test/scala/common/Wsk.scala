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

package common

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant

import scala.Left
import scala.Right
import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.mutable.Buffer
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.scalatest.Matchers

import TestUtils._
import common.TestUtils.RunResult
import spray.json.JsObject
import spray.json.JsValue
import spray.json.pimpString
import whisk.core.entity.ByteSize
import whisk.utils.retry

/**
 * Provide Scala bindings for the whisk CLI.
 *
 * Each of the top level CLI commands is a "noun" class that extends one
 * of several traits that are common to the whisk collections and corresponds
 * to one of the top level CLI nouns.
 *
 * Each of the "noun" classes mixes in the RunWskCmd trait which runs arbitrary
 * wsk commands and returns the results. Optionally RunWskCmd can validate the exit
 * code matched a desired value.
 *
 * The various collections support one or more of these as common traits:
 * list, get, delete, and sanitize.
 * Sanitize is akin to delete but accepts a failure because entity may not
 * exit. Additionally, some of the nouns define custom commands.
 *
 * All of the commands define default values that are either optional
 * or omitted in the common case. This makes for a compact implementation
 * instead of using a Builder pattern.
 *
 * An implicit WskProps instance is required for all of CLI commands. This
 * type provides the authentication key for the API as well as the namespace.
 * It also sets the apihost and apiversion explicitly to avoid ambiguity with
 * a local property file if it exists.
 */

case class WskProps(
    authKey: String = WhiskProperties.readAuthKey(WhiskProperties.getAuthFileForTesting),
    cert: String = WhiskProperties.getFileRelativeToWhiskHome("ansible/roles/nginx/files/openwhisk-client-cert.pem").getAbsolutePath,
    key: String = WhiskProperties.getFileRelativeToWhiskHome("ansible/roles/nginx/files/openwhisk-client-key.pem").getAbsolutePath ,
    namespace: String = "_",
    apiversion: String = "v1",
    apihost: String = WhiskProperties.getEdgeHost) {
    def overrides = Seq("-i", "--apihost", apihost, "--apiversion", apiversion)
}

case class WskPropsV2(
    authKey: String = WhiskProperties.readAuthKey(WhiskProperties.getAuthFileForTesting),
    namespace: String = "_",
    apiversion: String = "v1",
    apihost: String = WhiskProperties.getEdgeHost,
    token: String = "") {
    def overrides = Seq("-i", "--apihost", apihost, "--apiversion", apiversion)
    def writeFile(propsfile: File) = {
        val propsStr = s"NAMESPACE=${namespace}\nAPIVERSION=${apiversion}\nAUTH=${authKey}\nAPIHOST=${apihost}\nAPIGW_ACCESS_TOKEN=${token}\n"
        val bw = new BufferedWriter(new FileWriter(propsfile))
        bw.write(propsStr)
        bw.close()
    }
}

class Wsk() extends RunWskCmd {
    implicit val action = new WskAction
    implicit val trigger = new WskTrigger
    implicit val rule = new WskRule
    implicit val activation = new WskActivation
    implicit val pkg = new WskPackage
    implicit val namespace = new WskNamespace
    implicit val api = new WskApi
    implicit val apiexperimental = new WskApiExperimental
}

trait FullyQualifiedNames {
    /**
     * Fully qualifies the name of an entity with its namespace.
     * If the name already starts with the PATHSEP character, then
     * it already is fully qualified. Otherwise (package name or
     * basic entity name) it is prefixed with the namespace. The
     * namespace is derived from the implicit whisk properties.
     *
     * @param name to fully qualify iff it is not already fully qualified
     * @param wp whisk properties
     * @return name if it is fully qualified else a name fully qualified for a namespace
     */
    def fqn(name: String)(implicit wp: WskProps) = {
        val sep = "/" // Namespace.PATHSEP
        if (name.startsWith(sep)) name
        else s"$sep${wp.namespace}$sep$name"
    }

    /**
     * Resolves a namespace. If argument is defined, it takes precedence.
     * else resolve to namespace in implicit WskProps.
     *
     * @param namespace an optional namespace
     * @param wp whisk properties
     * @return resolved namespace
     */
    def resolve(namespace: Option[String])(implicit wp: WskProps) = {
        val sep = "/" // Namespace.PATHSEP
        namespace getOrElse s"$sep${wp.namespace}"
    }
}

trait ListOrGetFromCollection extends FullyQualifiedNames {
    self: RunWskCmd =>

    protected val noun: String

    /**
     * List entities in collection.
     *
     * @param namespace (optional) if specified must be  fully qualified namespace
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def list(
        namespace: Option[String] = None,
        limit: Option[Int] = None,
        time: Option[Boolean] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "list", resolve(namespace), "--auth", wp.authKey) ++
            { limit map { l => Seq("--limit", l.toString) } getOrElse Seq() } ++
            { time map { t => Seq("--time") } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }

    /**
     * Gets entity from collection.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def get(
        name: String,
        expectedExitCode: Int = SUCCESS_EXIT,
        summary: Boolean = false,
        fieldFilter: Option[String] = None,
        url: Option[Boolean] = None)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "get", "--auth", wp.authKey) ++
            Seq(fqn(name)) ++
            { if (summary) Seq("--summary") else Seq() } ++
            { fieldFilter map { f => Seq(f) } getOrElse Seq() } ++
            { url map { u => Seq("--url") } getOrElse Seq() }

        cli(wp.overrides ++ params, expectedExitCode)
    }
}

trait DeleteFromCollection extends FullyQualifiedNames {
    self: RunWskCmd =>

    protected val noun: String

    /**
     * Deletes entity from collection.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def delete(
        name: String,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        cli(wp.overrides ++ Seq(noun, "delete", "--auth", wp.authKey, fqn(name)), expectedExitCode)
    }

    /**
     * Deletes entity from collection but does not assert that the command succeeds.
     * Use this if deleting an entity that may not exist and it is OK if it does not.
     *
     * @param name either a fully qualified name or a simple entity name
     */
    def sanitize(name: String)(implicit wp: WskProps): RunResult = {
        delete(name, DONTCARE_EXIT)
    }
}

trait HasActivation {
    /**
     * Extracts activation id from invoke (action or trigger) or activation get
     */
    def extractActivationId(result: RunResult): Option[String] = {
        Try {
            // try to interpret the run result as the result of an invoke
            extractActivationIdFromInvoke(result) getOrElse extractActivationIdFromActivation(result).get
        } toOption
    }

    /**
     * Extracts activation id from 'wsk activation get' run result
     */
    private def extractActivationIdFromActivation(result: RunResult): Option[String] = {
        Try {
            // a characteristic string that comes right before the activationId
            val idPrefix = "ok: got activation "
            val output = if (result.exitCode != SUCCESS_EXIT) result.stderr else result.stdout
            assert(output.contains(idPrefix), output)
            extractActivationId(idPrefix, output).get
        } toOption
    }

    /**
     * Extracts activation id from 'wsk action invoke' or 'wsk trigger invoke'
     */
    private def extractActivationIdFromInvoke(result: RunResult): Option[String] = {
        Try {
            val output = if (result.exitCode != SUCCESS_EXIT) result.stderr else result.stdout
            assert(output.contains("ok: invoked") || output.contains("ok: triggered"), output)
            // a characteristic string that comes right before the activationId
            val idPrefix = "with id "
            extractActivationId(idPrefix, output).get
        } toOption
    }

    /**
     * Extracts activation id preceded by a prefix (idPrefix) from a string (output)
     *
     * @param idPrefix the prefix of the activation id
     * @param output the string to be used in the extraction
     * @return an option containing the id as a string or None if the extraction failed for any reason
     */
    private def extractActivationId(idPrefix: String, output: String): Option[String] = {
        Try {
            val start = output.indexOf(idPrefix) + idPrefix.length
            var end = start
            assert(start > 0)
            while (end < output.length && output.charAt(end) != '\n')
                end = end + 1
            output.substring(start, end) // a uuid
        } toOption
    }
}

class WskAction()
    extends RunWskCmd
    with ListOrGetFromCollection
    with DeleteFromCollection
    with HasActivation {

    override protected val noun = "action"

    /**
     * Creates action. Parameters mirror those available in the CLI.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        name: String,
        artifact: Option[String],
        kind: Option[String] = None, // one of docker, copy, sequence or none for autoselect else an explicit type
        main: Option[String] = None,
        docker: Option[String] = None,
        parameters: Map[String, JsValue] = Map(),
        annotations: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        annotationFile: Option[String] = None,
        timeout: Option[Duration] = None,
        memory: Option[ByteSize] = None,
        logsize: Option[ByteSize] = None,
        shared: Option[Boolean] = None,
        update: Boolean = false,
        web: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, if (!update) "create" else "update", "--auth", wp.authKey, fqn(name)) ++
            { artifact map { Seq(_) } getOrElse Seq() } ++
            {
                kind map { k =>
                    if (k == "sequence" || k == "copy" || k == "native") Seq(s"--$k")
                    else Seq("--kind", k)
                } getOrElse Seq()
            } ++
            { main.toSeq flatMap { p => Seq("--main", p) } } ++
            { docker.toSeq flatMap { p => Seq("--docker", p) } } ++
            { parameters flatMap { p => Seq("-p", p._1, p._2.compactPrint) } } ++
            { annotations flatMap { p => Seq("-a", p._1, p._2.compactPrint) } } ++
            { parameterFile map { pf => Seq("-P", pf) } getOrElse Seq() } ++
            { annotationFile map { af => Seq("-A", af) } getOrElse Seq() } ++
            { timeout map { t => Seq("-t", t.toMillis.toString) } getOrElse Seq() } ++
            { memory map { m => Seq("-m", m.toMB.toString) } getOrElse Seq() } ++
            { logsize map { l => Seq("-l", l.toMB.toString) } getOrElse Seq() } ++
            { shared map { s => Seq("--shared", if (s) "yes" else "no") } getOrElse Seq() } ++
            { web map { w => Seq("--web", w) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }

    /**
     * Invokes action. Parameters mirror those available in the CLI.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def invoke(
        name: String,
        parameters: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        blocking: Boolean = false,
        result: Boolean = false,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "invoke", "--auth", wp.authKey, fqn(name)) ++
            { parameters flatMap { p => Seq("-p", p._1, p._2.compactPrint) } } ++
            { parameterFile map { pf => Seq("-P", pf) } getOrElse Seq() } ++
            { if (blocking) Seq("--blocking") else Seq() } ++
            { if (result) Seq("--result") else Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }
}

class WskTrigger()
    extends RunWskCmd
    with ListOrGetFromCollection
    with DeleteFromCollection
    with HasActivation {

    override protected val noun = "trigger"

    /**
     * Creates trigger. Parameters mirror those available in the CLI.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        name: String,
        parameters: Map[String, JsValue] = Map(),
        annotations: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        annotationFile: Option[String] = None,
        feed: Option[String] = None,
        shared: Option[Boolean] = None,
        update: Boolean = false,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, if (!update) "create" else "update", "--auth", wp.authKey, fqn(name)) ++
            { feed map { f => Seq("--feed", fqn(f)) } getOrElse Seq() } ++
            { parameters flatMap { p => Seq("-p", p._1, p._2.compactPrint) } } ++
            { annotations flatMap { p => Seq("-a", p._1, p._2.compactPrint) } } ++
            { parameterFile map { pf => Seq("-P", pf) } getOrElse Seq() } ++
            { annotationFile map { af => Seq("-A", af) } getOrElse Seq() } ++
            { shared map { s => Seq("--shared", if (s) "yes" else "no") } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }

    /**
     * Fires trigger. Parameters mirror those available in the CLI.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def fire(
        name: String,
        parameters: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "fire", "--auth", wp.authKey, fqn(name)) ++
            { parameters flatMap { p => Seq("-p", p._1, p._2.compactPrint) } } ++
            { parameterFile map { pf => Seq("-P", pf) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }
}

class WskRule()
    extends RunWskCmd
    with ListOrGetFromCollection
    with DeleteFromCollection
    with WaitFor {

    override protected val noun = "rule"

    /**
     * Creates rule. Parameters mirror those available in the CLI.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param trigger must be a simple name
     * @param action must be a simple name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        name: String,
        trigger: String,
        action: String,
        annotations: Map[String, JsValue] = Map(),
        shared: Option[Boolean] = None,
        update: Boolean = false,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, if (!update) "create" else "update", "--auth", wp.authKey, fqn(name), (trigger), (action)) ++
            { annotations flatMap { p => Seq("-a", p._1, p._2.compactPrint) } } ++
            { shared map { s => Seq("--shared", if (s) "yes" else "no") } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }

    /**
     * Deletes rule.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    override def delete(
        name: String,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        super.delete(name, expectedExitCode)
    }

    /**
     * Enables rule.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def enable(
        name: String,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        cli(wp.overrides ++ Seq(noun, "enable", "--auth", wp.authKey, fqn(name)), expectedExitCode)
    }

    /**
     * Disables rule.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def disable(
        name: String,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        cli(wp.overrides ++ Seq(noun, "disable", "--auth", wp.authKey, fqn(name)), expectedExitCode)
    }

    /**
     * Checks state of rule.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def state(
        name: String,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        cli(wp.overrides ++ Seq(noun, "status", "--auth", wp.authKey, fqn(name)), expectedExitCode)
    }
}

class WskActivation()
    extends RunWskCmd
    with HasActivation
    with WaitFor {

    protected val noun = "activation"

    /**
     * Activation polling console.
     *
     * @param duration exits console after duration
     * @param since (optional) time travels back to activation since given duration
     */
    def console(
        duration: Duration,
        since: Option[Duration] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "poll", "--auth", wp.authKey, "--exit", duration.toSeconds.toString) ++
            { since map { s => Seq("--since-seconds", s.toSeconds.toString) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }

    /**
     * Lists activations.
     *
     * @param filter (optional) if define, must be a simple entity name
     * @param limit (optional) the maximum number of activation to return
     * @param since (optional) only the activations since this timestamp are included
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def list(
        filter: Option[String] = None,
        limit: Option[Int] = None,
        since: Option[Instant] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "list", "--auth", wp.authKey) ++
            { filter map { Seq(_) } getOrElse Seq() } ++
            { limit map { l => Seq("--limit", l.toString) } getOrElse Seq() } ++
            { since map { i => Seq("--since", i.toEpochMilli.toString) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }

    /**
     * Parses result of WskActivation.list to extract sequence of activation ids.
     *
     * @param rr run result, should be from WhiskActivation.list otherwise behavior is undefined
     * @return sequence of activations
     */
    def ids(rr: RunResult): Seq[String] = {
        rr.stdout.split("\n") filter {
            // remove empty lines the header
            s => s.nonEmpty && s != "activations"
        } map {
            // split into (id, name)
            _.split(" ")(0)
        }
    }

    /**
     * Gets activation by id.
     *
     * @param activationId the activation id
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     * @param last retrieves latest acitvation
     */
    def get(
        activationId: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT,
        fieldFilter: Option[String] = None,
        last: Option[Boolean] = None)(
            implicit wp: WskProps): RunResult = {
        val params =
          { activationId map { a => Seq(a) } getOrElse Seq() } ++
          { fieldFilter map { f => Seq(f) } getOrElse Seq() } ++
          { last map { l => Seq("--last") } getOrElse Seq() }
        cli(wp.overrides ++ Seq(noun, "get", "--auth", wp.authKey) ++ params, expectedExitCode)
    }

    /**
     * Gets activation logs by id.
     *
     * @param activationId the activation id
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     * @param last retrieves latest acitvation
     */
    def logs(
        activationId: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT,
        last: Option[Boolean] = None)(
            implicit wp: WskProps): RunResult = {
        val params =
          { activationId map { a => Seq(a) } getOrElse Seq() } ++
          { last map { l => Seq("--last") } getOrElse Seq() }
        cli(wp.overrides ++ Seq(noun, "logs", "--auth", wp.authKey) ++ params, expectedExitCode)
    }

    /**
     * Gets activation result by id.
     *
     * @param activationId the activation id
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     * @param last retrieves latest acitvation
     */
    def result(
        activationId: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT,
        last: Option[Boolean] = None)(
            implicit wp: WskProps): RunResult = {
        val params =
          { activationId map { a => Seq(a) } getOrElse Seq() } ++
          { last map { l => Seq("--last") } getOrElse Seq() }
        cli(wp.overrides ++ Seq(noun, "result", "--auth", wp.authKey) ++ params, expectedExitCode)
    }

    /**
     * Polls activations list for at least N activations. The activations
     * are optionally filtered for the given entity. Will return as soon as
     * N activations are found. If after retry budget is exhausted, N activations
     * are still not present, will return a partial result. Hence caller must
     * check length of the result and not assume it is >= N.
     *
     * @param N the number of activations desired
     * @param entity the name of the entity to filter from activation list
     * @param limit the maximum number of entities to list (if entity name is not unique use Some(0))
     * @param since (optional) only the activations since this timestamp are included
     * @param retries the maximum retries (total timeout is retries + 1 seconds)
     * @return activation ids found, caller must check length of sequence
     */
    def pollFor(
        N: Int,
        entity: Option[String],
        limit: Option[Int] = None,
        since: Option[Instant] = None,
        retries: Int = 10,
        pollPeriod: Duration = 1.second)(
            implicit wp: WskProps): Seq[String] = {
        Try {
            retry({
                val result = ids(list(filter = entity, limit = limit, since = since))
                if (result.length >= N) result else throw PartialResult(result)
            }, retries, waitBeforeRetry = Some(pollPeriod))
        } match {
            case Success(ids)                => ids
            case Failure(PartialResult(ids)) => ids
            case _                           => Seq()
        }
    }

    /**
     * Polls for an activation matching the given id. If found
     * return Right(activation) else Left(result of running CLI command).
     *
     * @return either Left(error message) or Right(activation as JsObject)
     */
    def waitForActivation(
        activationId: String,
        initialWait: Duration = 1 second,
        pollPeriod: Duration = 1 second,
        totalWait: Duration = 30 seconds)(
            implicit wp: WskProps): Either[String, JsObject] = {
        val activation = waitfor(() => {
            val result = cli(wp.overrides ++ Seq(noun, "get", activationId, "--auth", wp.authKey),
                expectedExitCode = DONTCARE_EXIT)
            if (result.exitCode == NOT_FOUND) {
                null
            } else if (result.exitCode == SUCCESS_EXIT) {
                Right(result.stdout)
            } else Left(s"$result")
        }, initialWait, pollPeriod, totalWait)

        Option(activation) map {
            case Right(stdout) =>
                Try {
                    // strip first line and convert the rest to JsObject
                    assert(stdout.startsWith("ok: got activation"))
                    parseJsonString(stdout)
                } map {
                    Right(_)
                } getOrElse Left(s"cannot parse activation from '$stdout'")
            case Left(error) => Left(error)
        } getOrElse Left(s"$activationId not found")
    }

    /** Used in polling for activations to record partial results from retry poll. */
    private case class PartialResult(ids: Seq[String]) extends Throwable
}

class WskNamespace()
    extends RunWskCmd
    with FullyQualifiedNames {

    protected val noun = "namespace"

    /**
     * Lists available namespaces for whisk properties.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def list(
        expectedExitCode: Int = SUCCESS_EXIT,
        time: Option[Boolean] = None)(
        implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "list", "--auth", wp.authKey) ++
            { time map { o => Seq("--time") } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }

    /**
     * Looks up namespace for whisk props.
     *
     * @param wskprops instance of WskProps with an auth key to lookup
     * @return namespace as string
     */
    def whois()(implicit wskprops: WskProps): String = {
        // the invariant that list() returns a conforming result is enforced in a test in WskBasicTests
        val ns = list().stdout.lines.toSeq.last.trim
        assert(ns != "_") // this is not permitted
        ns
    }

    /**
     * Gets entities in namespace.
     *
     * @param namespace (optional) if specified must be  fully qualified namespace
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def get(
        namespace: Option[String] = None,
        expectedExitCode: Int,
        time: Option[Boolean] = None)(
            implicit wp: WskProps): RunResult = {
            val params = { time map { t => Seq("--time") } getOrElse Seq() }
        cli(wp.overrides ++ Seq(noun, "get", resolve(namespace), "--auth", wp.authKey) ++ params, expectedExitCode)
    }
}

class WskPackage()
    extends RunWskCmd
    with ListOrGetFromCollection
    with DeleteFromCollection {
    override protected val noun = "package"

    /**
     * Creates package. Parameters mirror those available in the CLI.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        name: String,
        parameters: Map[String, JsValue] = Map(),
        annotations: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        annotationFile: Option[String] = None,
        shared: Option[Boolean] = None,
        update: Boolean = false,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, if (!update) "create" else "update", "--auth", wp.authKey, fqn(name)) ++
            { parameters flatMap { p => Seq("-p", p._1, p._2.compactPrint) } } ++
            { annotations flatMap { p => Seq("-a", p._1, p._2.compactPrint) } } ++
            { parameterFile map { pf => Seq("-P", pf) } getOrElse Seq() } ++
            { annotationFile map { af => Seq("-A", af) } getOrElse Seq() } ++
            { shared map { s => Seq("--shared", if (s) "yes" else "no") } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode)
    }

    /**
     * Binds package. Parameters mirror those available in the CLI.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def bind(
        provider: String,
        name: String,
        parameters: Map[String, JsValue] = Map(),
        annotations: Map[String, JsValue] = Map(),
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "bind", "--auth", wp.authKey, fqn(provider), fqn(name)) ++
            { parameters flatMap { p => Seq("-p", p._1, p._2.compactPrint) } } ++
            { annotations flatMap { p => Seq("-a", p._1, p._2.compactPrint) } }
        cli(wp.overrides ++ params, expectedExitCode)
    }
}

class WskApiExperimental extends RunWskCmd {
    protected val noun = "api-experimental"

    /**
     * Creates and API endpoint. Parameters mirror those available in the CLI.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        basepath: Option[String] = None,
        relpath: Option[String] = None,
        operation: Option[String] = None,
        action: Option[String] = None,
        apiname: Option[String] = None,
        swagger: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "create", "--auth", wp.authKey) ++
            { basepath map { b => Seq(b) } getOrElse Seq() } ++
            { relpath map { r => Seq(r) } getOrElse Seq() } ++
            { operation map { o => Seq(o) } getOrElse Seq() } ++
            { action map { aa => Seq(aa) } getOrElse Seq() } ++
            { apiname map { a => Seq("--apiname", a) } getOrElse Seq() } ++
            { swagger map { s => Seq("--config-file", s) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode, showCmd = true)
    }

    /**
     * Retrieve a list of API endpoints. Parameters mirror those available in the CLI.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def list(
        basepathOrApiName: Option[String] = None,
        relpath: Option[String] = None,
        operation: Option[String] = None,
        limit: Option[Int] = None,
        since: Option[Instant] = None,
        full: Option[Boolean] = None,
        time: Option[Boolean] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "list", "--auth", wp.authKey) ++
            { basepathOrApiName map { b => Seq(b) } getOrElse Seq() } ++
            { relpath map { r => Seq(r) } getOrElse Seq() } ++
            { operation map { o => Seq(o) } getOrElse Seq() } ++
            { limit map { l => Seq("--limit", l.toString) } getOrElse Seq() } ++
            { since map { i => Seq("--since", i.toEpochMilli.toString) } getOrElse Seq() } ++
            { full map { r => Seq("--full") } getOrElse Seq() } ++
            { time map { t => Seq("--time") } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode, showCmd = true)
    }

    /**
     * Retieves an API's configuration. Parameters mirror those available in the CLI.
     * Runs a command wsk [params] where the arguments come in as a sequence.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def get(
        basepathOrApiName: Option[String] = None,
        full: Option[Boolean] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "get", "--auth", wp.authKey) ++
            { basepathOrApiName map { b => Seq(b) } getOrElse Seq() } ++
            { full map { f => if (f) Seq("--full") else Seq() } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode, showCmd = true)
    }

    /**
     * Delete an entire API or a subset of API endpoints. Parameters mirror those available in the CLI.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def delete(
        basepathOrApiName: String,
        relpath: Option[String] = None,
        operation: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "delete", "--auth", wp.authKey, basepathOrApiName) ++
            { relpath map { r => Seq(r) } getOrElse Seq() } ++
            { operation map { o => Seq(o) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode, showCmd = true)
    }
}

class WskApi()
    extends RunWskCmd {
    protected val noun = "api"

    /**
     * Creates and API endpoint. Parameters mirror those available in the CLI.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        basepath: Option[String] = None,
        relpath: Option[String] = None,
        operation: Option[String] = None,
        action: Option[String] = None,
        apiname: Option[String] = None,
        swagger: Option[String] = None,
        responsetype: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT,
        cliCfgFile: Option[String] = None)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "create", "--auth", wp.authKey) ++
            { basepath map { b => Seq(b) } getOrElse Seq() } ++
            { relpath map { r => Seq(r) } getOrElse Seq() } ++
            { operation map { o => Seq(o) } getOrElse Seq() } ++
            { action map { aa => Seq(aa) } getOrElse Seq() } ++
            { apiname map { a => Seq("--apiname", a) } getOrElse Seq() } ++
            { swagger map { s => Seq("--config-file", s) } getOrElse Seq() } ++
            { responsetype map { t => Seq("--response-type", t) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode, showCmd = true, env = Map("WSK_CONFIG_FILE" -> cliCfgFile.getOrElse("")))
    }

    /**
     * Retrieve a list of API endpoints. Parameters mirror those available in the CLI.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def list(
        basepathOrApiName: Option[String] = None,
        relpath: Option[String] = None,
        operation: Option[String] = None,
        limit: Option[Int] = None,
        since: Option[Instant] = None,
        full: Option[Boolean] = None,
        time: Option[Boolean] = None,
        expectedExitCode: Int = SUCCESS_EXIT,
        cliCfgFile: Option[String] = None)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "list", "--auth", wp.authKey) ++
            { basepathOrApiName map { b => Seq(b) } getOrElse Seq() } ++
            { relpath map { r => Seq(r) } getOrElse Seq() } ++
            { operation map { o => Seq(o) } getOrElse Seq() } ++
            { limit map { l => Seq("--limit", l.toString) } getOrElse Seq() } ++
            { since map { i => Seq("--since", i.toEpochMilli.toString) } getOrElse Seq() } ++
            { full map { r => Seq("--full") } getOrElse Seq() } ++
            { time map { o => Seq("--time") } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode, showCmd = true, env = Map("WSK_CONFIG_FILE" -> cliCfgFile.getOrElse("")))
    }

    /**
     * Retieves an API's configuration. Parameters mirror those available in the CLI.
     * Runs a command wsk [params] where the arguments come in as a sequence.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def get(
        basepathOrApiName: Option[String] = None,
        full: Option[Boolean] = None,
        expectedExitCode: Int = SUCCESS_EXIT,
        cliCfgFile: Option[String] = None,
        format: Option[String] = None)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "get", "--auth", wp.authKey) ++
            { basepathOrApiName map { b => Seq(b) } getOrElse Seq() } ++
            { full map { f => if (f) Seq("--full") else Seq() } getOrElse Seq() } ++
            { format map { ft => Seq("--format", ft) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode, showCmd = true, env = Map("WSK_CONFIG_FILE" -> cliCfgFile.getOrElse("")))
    }

    /**
     * Delete an entire API or a subset of API endpoints. Parameters mirror those available in the CLI.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def delete(
        basepathOrApiName: String,
        relpath: Option[String] = None,
        operation: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT,
        cliCfgFile: Option[String] = None)(
            implicit wp: WskProps): RunResult = {
        val params = Seq(noun, "delete", "--auth", wp.authKey, basepathOrApiName) ++
            { relpath map { r => Seq(r) } getOrElse Seq() } ++
            { operation map { o => Seq(o) } getOrElse Seq() }
        cli(wp.overrides ++ params, expectedExitCode, showCmd = true, env = Map("WSK_CONFIG_FILE" -> cliCfgFile.getOrElse("")))
    }
}

trait WaitFor {
    /**
     * Waits up to totalWait seconds for a 'step' to return value.
     * Often tests call this routine immediately after starting work.
     * Performs an initial wait before entering poll loop.
     */
    def waitfor[T](
        step: () => T,
        initialWait: Duration = 1 second,
        pollPeriod: Duration = 1 second,
        totalWait: Duration = 30 seconds): T = {
        Thread.sleep(initialWait.toMillis)
        val endTime = System.currentTimeMillis() + totalWait.toMillis
        while (System.currentTimeMillis() < endTime) {
            val predicate = step()
            predicate match {
                case (t: Boolean) if t =>
                    return predicate
                case (t: Any) if t != null && !t.isInstanceOf[Boolean] =>
                    return predicate
                case _ if System.currentTimeMillis() >= endTime =>
                    return predicate
                case _ =>
                    Thread.sleep(pollPeriod.toMillis)
            }
        }
        null.asInstanceOf[T]
    }
}

object Wsk {
    private val binaryName = "wsk"
    private val cliPath = if (WhiskProperties.useCLIDownload) getDownloadedGoCLIPath else WhiskProperties.getCLIPath

    assert((new File(cliPath)).exists, s"did not find $cliPath")

    /** What is the path to a downloaded CLI? **/
    private def getDownloadedGoCLIPath = {
        s"${System.getProperty("user.home")}${File.separator}.local${File.separator}bin${File.separator}${binaryName}"
    }

    def baseCommand = Buffer(cliPath)
}

trait RunWskCmd extends Matchers {

    /**
     * The base command to run.
     */
    def baseCommand = Wsk.baseCommand

    /**
     * Runs a command wsk [params] where the arguments come in as a sequence.
     *
     * @return RunResult which contains stdout, stderr, exit code
     */
    def cli(params: Seq[String],
            expectedExitCode: Int = SUCCESS_EXIT,
            verbose: Boolean = false,
            env: Map[String, String] = Map("WSK_CONFIG_FILE" -> ""),
            workingDir: File = new File("."),
            stdinFile: Option[File] = None,
            showCmd: Boolean = false,
            hideFromOutput: Seq[String] = Seq()): RunResult = {
        val args = baseCommand
        if (verbose) args += "--verbose"
        if (showCmd) println(args.mkString(" ") + " " + params.mkString(" "))
        val rr = TestUtils.runCmd(DONTCARE_EXIT, workingDir, TestUtils.logger, sys.env ++ env, stdinFile.getOrElse(null), args ++ params: _*)

        withClue(hideStr(reportFailure(args ++ params, expectedExitCode, rr).toString(), hideFromOutput)) {
            if (expectedExitCode != TestUtils.DONTCARE_EXIT) {
                val ok = (rr.exitCode == expectedExitCode) || (expectedExitCode == TestUtils.ANY_ERROR_EXIT && rr.exitCode != 0)
                if (!ok) {
                    rr.exitCode shouldBe expectedExitCode
                }
            }
        }

        rr
    }

    // Takes a string and a list of sensitive strings. Any sensistive string found in
    // the target string will be replaced with "XXXXX", returning the processed string
    def hideStr(str: String, hideThese: Seq[String]): String = {
        // Iterate through each string to hide, replacing it in the target string (str)
        hideThese.fold(str)((updatedStr, replaceThis) => updatedStr.replace(replaceThis, "XXXXX"))
    }

    /*
     * Utility function to return a JSON object from the CLI output that returns
     * an optional a status line following by the JSON data
     */
    def parseJsonString(jsonStr: String): JsObject = {
        jsonStr.substring(jsonStr.indexOf("\n") + 1).parseJson.asJsObject // Skip optional status line before parsing
    }

    private def reportFailure(args: Buffer[String], ec: Integer, rr: RunResult) = {
        val s = new StringBuilder()
        s.append(args.mkString(" ") + "\n")
        if (rr.stdout.nonEmpty) s.append(rr.stdout + "\n")
        if (rr.stderr.nonEmpty) s.append(rr.stderr)
        s.append("exit code:")
    }
}

object WskAdmin {
    private val binDir = WhiskProperties.getFileRelativeToWhiskHome("bin")
    private val binaryName = "wskadmin"

    def exists = {
        val dir = binDir
        val exec = new File(dir, binaryName)
        assert(dir.exists, s"did not find $dir")
        assert(exec.exists, s"did not find $exec")
    }

    def baseCommand = {
        Buffer(WhiskProperties.python, new File(binDir, binaryName).toString)
    }

    def listKeys(namespace: String, pick: Integer = 1): List[(String, String)] = {
        val wskadmin = new RunWskAdminCmd {}
        wskadmin.cli(Seq("user", "list", namespace, "--pick", pick.toString))
            .stdout
            .split("\n")
            .map("""\s+""".r.split(_))
            .map(parts => (parts(0), parts(1)))
            .toList
    }
}

trait RunWskAdminCmd extends RunWskCmd {
    override def baseCommand = WskAdmin.baseCommand
}
