package distopt

import org.apache.spark.{SparkContext, SparkConf}
import distopt.utils._
import distopt.solvers._
import breeze.linalg.DenseVector


object driver {

  def main(args: Array[String]) {

    val options =  args.map { arg =>
      arg.dropWhile(_ == '-').split('=') match {
        case Array(opt, v) => (opt -> v)
        case Array(opt) => (opt -> "true")
        case _ => throw new IllegalArgumentException("Invalid argument: " + arg)
      }
    }.toMap

    // read in inputs
    val master = options.getOrElse("master", "local[4]")
    val trainFile = options.getOrElse("trainFile", "")
    val numFeatures = options.getOrElse("numFeatures", "0").toInt
    val numSplits = options.getOrElse("numSplits","1").toInt
    val chkptDir = options.getOrElse("chkptDir","");
    var chkptIter = options.getOrElse("chkptIter","100").toInt
    val testFile = options.getOrElse("testFile", "")
    val justCoCoA = options.getOrElse("justCoCoA", "true").toBoolean // set to false to compare different methods

    // algorithm-specific inputs
    val lambda = options.getOrElse("lambda", "0.01").toDouble // regularization parameter
    val numRounds = options.getOrElse("numRounds", "200").toInt // number of outer iterations, called T in the paper
    val localIterFrac = options.getOrElse("localIterFrac","1.0").toDouble; // fraction of local points to be processed per round, H = localIterFrac * n
    val beta = options.getOrElse("beta","1.0").toDouble;  // scaling parameter when combining the updates of the workers (1=averaging for CoCoA)
    val gamma = options.getOrElse("gamma","1.0").toDouble;  // aggregation parameter for CoCoA+ (1=adding, 1/K=averaging)
    val debugIter = options.getOrElse("debugIter","10").toInt // set to -1 to turn off debugging output
    val seed = options.getOrElse("seed","0").toInt // set seed for debug purposes

    // print out inputs
    println("master:       " + master);          println("trainFile:    " + trainFile);
    println("numFeatures:  " + numFeatures);     println("numSplits:    " + numSplits);
    println("chkptDir:     " + chkptDir);        println("chkptIter     " + chkptIter);       
    println("testfile:     " + testFile);        println("justCoCoA     " + justCoCoA);       
    println("lambda:       " + lambda);          println("numRounds:    " + numRounds);       
    println("localIterFrac:" + localIterFrac);   println("beta          " + beta);     
    println("gamma         " + beta);            println("debugIter     " + debugIter);       
    println("seed          " + seed);   

    // start spark context
    val conf = new SparkConf().setMaster(master)
      .setAppName("demoCoCoA")
      .setJars(SparkContext.jarOfObject(this).toSeq)
    val sc = new SparkContext(conf)
    if (chkptDir != "") {
      sc.setCheckpointDir(chkptDir)
    } else {
      chkptIter = numRounds + 1
    }

    // read in data
    val data = OptUtils.loadLIBSVMData(sc,trainFile,numSplits,numFeatures).cache()
    val n = data.count().toInt // number of data examples
    val testData = {
      if (testFile != ""){ OptUtils.loadLIBSVMData(sc,testFile,numSplits,numFeatures).cache() }
      else { null }
    }

    // compute H, # of local iterations
    var localIters = (localIterFrac * n / data.partitions.size).toInt
    localIters = Math.max(localIters,1)

    // for the primal-dual algorithms to run correctly, the initial primal vector has to be zero 
    // (corresponding to dual alphas being zero)
    val wInit = DenseVector.zeros[Double](numFeatures)

    // set to solve hingeloss SVM
    val loss = OptUtils.hingeLoss _
    val params = Params(loss, n, wInit, numRounds, localIters, lambda, beta, gamma)
    val debug = DebugParams(testData, debugIter, seed, chkptIter)


    // run CoCoA+
    val (finalwCoCoAPlus, finalalphaCoCoAPlus) = CoCoA.runCoCoA(data, params, debug, plus=true)
    OptUtils.printSummaryStatsPrimalDual("CoCoA+", data, finalwCoCoAPlus, finalalphaCoCoAPlus, lambda, testData)

    // run CoCoA
    val (finalwCoCoA, finalalphaCoCoA) = CoCoA.runCoCoA(data, params, debug, plus=false)
    OptUtils.printSummaryStatsPrimalDual("CoCoA", data, finalwCoCoA, finalalphaCoCoA, lambda, testData)


    // optionally run other methods for comparison
    if(!justCoCoA) { 

      // run Mini-batch CD
     val (finalwMbCD, finalalphaMbCD) = MinibatchCD.runMbCD(data, params, debug)
     OptUtils.printSummaryStatsPrimalDual("Mini-batch CD", data, finalwMbCD, finalalphaMbCD, lambda, testData)

     // run Mini-batch SGD
     val finalwMbSGD = SGD.runSGD(data, params, debug, local=false)
     OptUtils.printSummaryStats("Mini-batch SGD", data, finalwMbSGD, lambda, testData)
    
     // run Local-SGD
     val finalwLocalSGD = SGD.runSGD(data, params, debug, local=true)
     OptUtils.printSummaryStats("Local SGD", data, finalwLocalSGD, lambda, testData)

    }

    sc.stop()
   }
}
