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
package org.apache.lucene.store;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsWriter;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.TestIndexWriterReader;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.mockfile.WindowsFS;
import org.apache.lucene.tests.store.BaseDirectoryTestCase;
import org.apache.lucene.tests.store.MockDirectoryWrapper;
import org.apache.lucene.tests.util.TestUtil;

public class TestFileSwitchDirectory extends BaseDirectoryTestCase {

  /** Test if writing doc stores to disk and everything else to ram works. */
  public void testBasic() throws IOException {
    Set<String> fileExtensions = new HashSet<>();
    fileExtensions.add(Lucene90CompressingStoredFieldsWriter.FIELDS_EXTENSION);
    fileExtensions.add("fdx");
    fileExtensions.add("fdm");

    MockDirectoryWrapper primaryDir =
        new MockDirectoryWrapper(random(), new ByteBuffersDirectory());
    primaryDir.setCheckIndexOnClose(false); // only part of an index
    MockDirectoryWrapper secondaryDir =
        new MockDirectoryWrapper(random(), new ByteBuffersDirectory());
    secondaryDir.setCheckIndexOnClose(false); // only part of an index

    FileSwitchDirectory fsd =
        new FileSwitchDirectory(fileExtensions, primaryDir, secondaryDir, true);
    // for now we wire the default codec because we rely upon its specific impl
    IndexWriter writer =
        new IndexWriter(
            fsd,
            new IndexWriterConfig(new MockAnalyzer(random()))
                .setMergePolicy(newLogMergePolicy(false))
                .setCodec(TestUtil.getDefaultCodec())
                .setUseCompoundFile(false));
    TestIndexWriterReader.createIndexNoClose(true, "ram", writer);
    IndexReader reader = DirectoryReader.open(writer);
    assertEquals(100, reader.maxDoc());
    writer.commit();
    // we should see only fdx,fdt files here
    String[] files = primaryDir.listAll();
    assertTrue(files.length > 0);
    for (String file : files) {
      String ext = FileSwitchDirectory.getExtension(file);
      assertTrue(fileExtensions.contains(ext));
    }
    files = secondaryDir.listAll();
    assertTrue(files.length > 0);
    // we should not see fdx,fdt files here
    for (String file : files) {
      String ext = FileSwitchDirectory.getExtension(file);
      assertFalse(fileExtensions.contains(ext));
    }
    reader.close();
    writer.close();

    files = fsd.listAll();
    for (String file : files) {
      assertNotNull(file);
    }
    fsd.close();
  }

  private Directory newFSSwitchDirectory(Set<String> primaryExtensions) throws IOException {
    Path primDir = createTempDir("foo");
    Path secondDir = createTempDir("bar");
    return newFSSwitchDirectory(primDir, secondDir, primaryExtensions);
  }

  private Directory newFSSwitchDirectory(Path aDir, Path bDir, Set<String> primaryExtensions)
      throws IOException {
    Directory a = new NIOFSDirectory(aDir);
    Directory b = new NIOFSDirectory(bDir);
    return new FileSwitchDirectory(primaryExtensions, a, b, true);
  }

  // LUCENE-3380 -- make sure we get exception if the directory really does not exist.
  @Override
  public void testNoDir() throws Throwable {
    Path primDir = createTempDir("foo");
    Path secondDir = createTempDir("bar");
    Directory dir = newFSSwitchDirectory(primDir, secondDir, Collections.emptySet());
    expectThrows(IndexNotFoundException.class, () -> DirectoryReader.open(dir));

    dir.close();
  }

  public void testRenameTmpFile() throws IOException {
    try (Directory directory = getDirectory(createTempDir())) {
      final String name;
      try (IndexOutput out = directory.createTempOutput("foo.cfs", "", IOContext.DEFAULT)) {
        out.writeInt(1);
        name = out.getName();
      }
      assertEquals(1, Arrays.stream(directory.listAll()).filter(f -> f.equals(name)).count());
      assertEquals(0, Arrays.stream(directory.listAll()).filter(f -> f.equals("foo.cfs")).count());
      directory.rename(name, "foo.cfs");
      assertEquals(1, Arrays.stream(directory.listAll()).filter(f -> f.equals("foo.cfs")).count());
      assertEquals(0, Arrays.stream(directory.listAll()).filter(f -> f.equals(name)).count());
    }

    try (Directory directory = newFSSwitchDirectory(Collections.singleton("bar"))) {
      String brokenName;
      try (IndexOutput out = directory.createTempOutput("foo", "bar", IOContext.DEFAULT)) {
        out.writeInt(1);
        brokenName = out.getName();
      }
      AtomicMoveNotSupportedException exception =
          expectThrows(
              AtomicMoveNotSupportedException.class, () -> directory.rename(brokenName, "foo.bar"));
      assertEquals(
          "foo_bar_0.tmp -> foo.bar: source and dest are in different directories",
          exception.getMessage());
    }
  }

  @Override
  protected Directory getDirectory(Path path) throws IOException {
    Set<String> extensions = new HashSet<>();
    if (random().nextBoolean()) {
      extensions.add("cfs");
    }
    if (random().nextBoolean()) {
      extensions.add("prx");
    }
    if (random().nextBoolean()) {
      extensions.add("frq");
    }
    if (random().nextBoolean()) {
      extensions.add("tip");
    }
    if (random().nextBoolean()) {
      extensions.add("tim");
    }
    if (random().nextBoolean()) {
      extensions.add("del");
    }
    return newFSSwitchDirectory(extensions);
  }

  public void testDeleteAndList() throws IOException {
    // relies on windows semantics
    Path path = createTempDir();
    WindowsFS provider = new WindowsFS(path.getFileSystem());
    Path indexPath = provider.wrapPath(path);
    try (final FileSwitchDirectory dir =
        new FileSwitchDirectory(
            Collections.singleton("tim"),
            new NIOFSDirectory(indexPath),
            new NIOFSDirectory(indexPath),
            true)) {
      dir.createOutput("foo.tim", IOContext.DEFAULT).close();
      Function<String[], Long> stripExtra =
          array -> Arrays.stream(array).filter(f -> f.startsWith("extra") == false).count();
      try (IndexInput indexInput = dir.openInput("foo.tim", IOContext.DEFAULT)) {
        assert indexInput != null;
        dir.deleteFile("foo.tim");
        assertEquals(1, dir.getPrimaryDir().getPendingDeletions().size());
        assertEquals(1, dir.getPendingDeletions().size());
        assertEquals(0, stripExtra.apply(dir.listAll()).intValue());
        assertEquals(0, stripExtra.apply(dir.getPrimaryDir().listAll()).intValue());
        assertEquals(1, stripExtra.apply(dir.getSecondaryDir().listAll()).intValue());
      }
      assertEquals(0, dir.getPrimaryDir().getPendingDeletions().size());
      assertEquals(0, dir.getPendingDeletions().size());
      assertEquals(0, stripExtra.apply(dir.listAll()).intValue());
      assertEquals(0, stripExtra.apply(dir.getPrimaryDir().listAll()).intValue());
      assertEquals(0, stripExtra.apply(dir.getSecondaryDir().listAll()).intValue());
    }
  }
}
