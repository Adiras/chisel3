// See LICENSE for license details.

package chisel3.testers

import chisel3._
import java.io._

import chisel3.aop.Aspect
import chisel3.experimental.{RunFirrtlTransform, RunFirrtlTransforms}
import chisel3.stage.AspectStage
import firrtl.{Driver => _, _}
import firrtl.transforms.BlackBoxSourceHelper.writeResourceToDirectory

object TesterDriver extends BackendCompilationUtilities {

  /** For use with modules that should successfully be elaborated by the
    * frontend, and which can be turned into executables with assertions. */
  def execute(t: () => BasicTester,
              additionalVResources: Seq[String] = Seq(),
              aspects: Seq[Aspect[_]] = Seq()
             ): Boolean = {
    // Invoke the chisel compiler to get the circuit's IR
    val circuit = Driver.elaborate(finishWrapper(t))

    val aspectedCircuit = circuit.copy(annotations = aspects ++ circuit.annotations)

    // Set up a bunch of file handlers based on a random temp filename,
    // plus the quirks of Verilator's naming conventions
    val target = aspectedCircuit.name

    val path = createTestDirectory(target)
    val fname = new File(path, target)

    // For now, dump the IR out to a file
    Driver.dumpFirrtl(aspectedCircuit, Some(new File(fname.toString + ".fir")))
    val firrtlCircuit = Driver.toFirrtl(aspectedCircuit)

    // Copy CPP harness and other Verilog sources from resources into files
    val cppHarness =  new File(path, "top.cpp")
    copyResourceToFile("/chisel3/top.cpp", cppHarness)
    // NOTE: firrtl.Driver.execute() may end up copying these same resources in its BlackBoxSourceHelper code.
    // As long as the same names are used for the output files, and we avoid including duplicate files
    //  in BackendCompilationUtilities.verilogToCpp(), we should be okay.
    // To that end, we use the same method to write the resource to the target directory.
    val additionalVFiles = additionalVResources.map((name: String) => {
      writeResourceToDirectory(name, path)
    })

    // Compile firrtl
    val transforms = aspectedCircuit.annotations.flatMap {
      case anno: RunFirrtlTransforms => anno.transformClasses
      case _ => Nil
    }.distinct
     .filterNot(_ == classOf[Transform])
     .map { transformClass: Class[_ <: Transform] => transformClass.newInstance() }
    val newAnnotations = aspectedCircuit.annotations.map(_.toFirrtl).toList
    val resolvedAnnotations = new AspectStage().run(newAnnotations).toList
    val optionsManager = new ExecutionOptionsManager("chisel3") with HasChiselExecutionOptions with HasFirrtlOptions {
      commonOptions = CommonOptions(topName = target, targetDirName = path.getAbsolutePath)
      firrtlOptions = FirrtlExecutionOptions(compilerName = "verilog", annotations = resolvedAnnotations,
                                             customTransforms = transforms,
                                             firrtlCircuit = Some(firrtlCircuit))
    }
    firrtl.Driver.execute(optionsManager) match {
      case _: FirrtlExecutionFailure => return false
      case _ =>
    }

    // Use sys.Process to invoke a bunch of backend stuff, then run the resulting exe
    if ((verilogToCpp(target, path, additionalVFiles, cppHarness) #&&
        cppToExe(target, path)).! == 0) {
      executeExpectingSuccess(target, path)
    } else {
      false
    }
  }
  /**
    * Calls the finish method of an BasicTester or a class that extends it.
    * The finish method is a hook for code that augments the circuit built in the constructor.
    */
  def finishWrapper(test: () => BasicTester): () => BasicTester = {
    () => {
      val tester = test()
      tester.finish()
      tester
    }
  }
}
