/*
 * Copyright 2015 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.webindex.data;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;

import io.fluo.webindex.core.DataConfig;
import io.fluo.webindex.data.spark.IndexEnv;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaFutureAction;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Copy {

  private static final Logger log = LoggerFactory.getLogger(Copy.class);

  public static String getFilename(String fullPath) {
    int slashIndex = fullPath.lastIndexOf("/");
    if (slashIndex == -1) {
      return fullPath;
    }
    return fullPath.substring(slashIndex + 1);
  }

  public static void main(String[] args) throws Exception {

    if (args.length != 3) {
      log.error("Usage: Copy <pathsFile> <range> <dest>");
      System.exit(1);
    }
    final String hadoopConfDir = IndexEnv.getHadoopConfDir();
    final String ccPaths = args[0];
    if (!(new File(ccPaths).exists())) {
      log.error("CC paths file {} does not exist", ccPaths);
      System.exit(1);
    }
    int start = 0;
    int end = 0;
    try {
      start = Integer.parseInt(args[1].split("-")[0]);
      end = Integer.parseInt(args[1].split("-")[1]);
    } catch (NumberFormatException e) {
      log.error("Invalid range: {}", args[1]);
      System.exit(1);
    }
    DataConfig dataConfig = DataConfig.load();

    SparkConf sparkConf = new SparkConf().setAppName("Webindex-Copy");
    JavaSparkContext ctx = new JavaSparkContext(sparkConf);

    FileSystem hdfs = FileSystem.get(ctx.hadoopConfiguration());
    Path destPath = new Path(args[2]);
    if (!hdfs.exists(destPath)) {
      hdfs.mkdirs(destPath);
    }

    JavaRDD<String> allFiles = ctx.textFile("file://" + ccPaths);

    List<String> copyList = allFiles.takeOrdered(end + 1).subList(start, end + 1);

    log.info("Copying {} files (Range {} of paths file {}) from AWS to HDFS {}", copyList.size(),
        args[1], args[0], destPath.toString());

    JavaRDD<String> copyRDD = ctx.parallelize(copyList, dataConfig.sparkExecutorInstances);

    final String prefix = DataConfig.CC_URL_PREFIX;
    final String destDir = destPath.toString();

    copyRDD.foreachPartition(iter -> {
      FileSystem fs = IndexEnv.getHDFS(hadoopConfDir);
      iter.forEachRemaining(ccPath -> {
        try {
          Path dfsPath = new Path(destDir + "/" + getFilename(ccPath));
          if (fs.exists(dfsPath)) {
            log.error("File {} exists in HDFS and should have been previously filtered",
                dfsPath.getName());
          } else {
            String urlToCopy = prefix + ccPath;
            log.info("Starting copy of {} to {}", urlToCopy, destDir);
            try (OutputStream out = fs.create(dfsPath);
                BufferedInputStream in = new BufferedInputStream(new URL(urlToCopy).openStream())) {
              IOUtils.copy(in, out);
            }
            log.info("Created {}", dfsPath.getName());
          }
        } catch (IOException e) {
          log.error("Exception while copying {}", ccPath, e);
        }
      });
    });
    ctx.stop();
  }
}
