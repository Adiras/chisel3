// See LICENSE for license details.

package chiselTests.stage

import chisel3._
import chisel3.stage.ChiselMain

import java.io.File

import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

object ChiselMainSpec {

  /** A module that connects two different types together resulting in an elaboration error */
  class DifferentTypesModule extends RawModule {
    val in = IO(UInt(1.W))
    val out = IO(SInt(1.W))
    out := in
  }

  /** A simple module */
  class SimpleModule extends RawModule {
    val in = IO(UInt(1.W))
    val out = IO(UInt(1.W))
    out := in
  }

}

class ChiselMainSpec extends FeatureSpec with GivenWhenThen with Matchers with chiselTests.Utils {

  import ChiselMainSpec._

  class ChiselMainFixture {
    Given("a Chisel stage")
    val stage = ChiselMain
  }

  class TargetDirectoryFixture(dirName: String) {
    val dir = new File(s"test_run_dir/FirrtlStageSpec/$dirName")
    val buildDir = new File(dir + "/build")
    dir.mkdirs()
  }

  case class ChiselMainTest(
    args: Array[String],
    generator: Option[Class[_ <: RawModule]] = None,
    files: Seq[String] = Seq.empty,
    stdout: Option[String] = None,
    stderr: Option[String] = None,
    result: Int = 0) {
    def testName: String = "args" + args.mkString("_")
    def argsString: String = args.mkString(" ")
  }

  def runStageExpectFiles(p: ChiselMainTest): Unit = {
    scenario(s"""User runs Chisel Stage with '${p.argsString}'""") {
      val f = new ChiselMainFixture
      val td = new TargetDirectoryFixture(p.testName)

      p.files.foreach( f => new File(td.buildDir + s"/$f").delete() )

      When(s"""the user tries to compile with '${p.argsString}'""")
      val (stdout, stderr, result) =
        grabStdOutErr {
          catchStatus {
            val module: Array[String] = Array("foo") ++
              (if (p.generator.nonEmpty) { Array("--module", p.generator.get.getName) }
               else                      { Array.empty[String]                        })
            f.stage.main(Array("-td", td.buildDir.toString) ++ module ++ p.args)
          }
        }

      p.stdout match {
        case Some(a) =>
          Then(s"""STDOUT should include "$a"""")
          stdout should include (a)
        case None =>
          Then(s"nothing should print to STDOUT")
          stdout should be (empty)
      }

      p.stderr match {
        case Some(a) =>
          And(s"""STDERR should include "$a"""")
          stderr should include (a)
        case None =>
          And(s"nothing should print to STDERR")
          stderr should be (empty)
      }

      p.result match {
        case 0 =>
          And(s"the exit code should be 0")
          result shouldBe a [Right[_,_]]
        case a =>
          And(s"the exit code should be $a")
          result shouldBe (Left(a))
      }

      p.files.foreach { f =>
        And(s"file '$f' should be emitted in the target directory")
        val out = new File(td.buildDir + s"/$f")
        out should (exist)
      }
    }
  }

  info("As a Chisel user")
  info("I screw up and compile some bad code")
  feature("Stack trace trimming") {
    Seq(
      ChiselMainTest(args = Array("-X", "low"),
                     generator = Some(classOf[DifferentTypesModule]),
                     stdout = Some("Stack trace trimmed to user code only"),
                     result = 1),
      ChiselMainTest(args = Array("-X", "high", "--full-stacktrace"),
                     generator = Some(classOf[DifferentTypesModule]),
                     stdout = Some("org.scalatest"),
                     result = 1)
    ).foreach(runStageExpectFiles)
  }

  info("A a Chisel user")
  info("I want to export my stash")
  feature("Stash export as cache") {
    Seq(
      ChiselMainTest(args = Array("-X", "low", "--export-cache", "test::test_run_dir/FirrtlStageSpec/artifacts"),
        generator = Some(classOf[SimpleModule]),
        files = Seq("artifacts/blah.cache")
      )
    ).foreach(runStageExpectFiles)

  }

}
