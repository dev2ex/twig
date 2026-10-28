package com.twig.app;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class TrashMoves {
  private static native int nativeRename(byte[] from, byte[] to);
  static int move(Path from, Path to) { return nativeRename(from.toString().getBytes(StandardCharsets.UTF_8), to.toString().getBytes(StandardCharsets.UTF_8)); }
  public static void main(String[] args) throws Exception {
    System.load(args[0]);
    Path dir=Files.createTempDirectory("twig-trash-native-");
    try {
      Path source=Files.writeString(dir.resolve("原始😀.txt"),"original");
      Path target=Files.writeString(dir.resolve("target.txt"),"existing");
      if(move(source,target)==0 || !Files.readString(target).equals("existing") || !Files.readString(source).equals("original")) throw new AssertionError("overwrote existing file");
      Files.delete(target);
      if(move(source,target)!=0 || Files.exists(source) || !Files.readString(target).equals("original")) throw new AssertionError("UTF-8 move failed");
      Path a=Files.writeString(dir.resolve("a"),"A"), b=Files.writeString(dir.resolve("b"),"B"), dest=dir.resolve("race");
      CountDownLatch start=new CountDownLatch(1);
      ExecutorService pool=Executors.newFixedThreadPool(2);
      try {
        Future<Integer> x=pool.submit(()->{start.await();return move(a,dest);});
        Future<Integer> y=pool.submit(()->{start.await();return move(b,dest);});
        start.countDown();
        int first=x.get(), second=y.get();
        if((first==0)==(second==0) || Files.exists(a)==Files.exists(b)) throw new AssertionError("race did not preserve losing source");
        System.out.println("PASS: collision refusal, UTF-8 supplementary filenames, concurrent destination creation (one winner; losing source retained)");
      } finally { pool.shutdownNow(); }
      Path folder=Files.createDirectory(dir.resolve("folder")), restored=dir.resolve("restored");
      Files.writeString(folder.resolve("inside"),"keep");
      Path occupied=Files.createDirectory(dir.resolve("occupied"));
      if(move(folder,occupied)==0 || !Files.exists(folder.resolve("inside"))) throw new AssertionError("overwrote existing empty directory");
      Files.delete(occupied);
      Path impossible=folder.resolve("child");
      if(move(folder,impossible)==0 || Files.exists(impossible) || !Files.exists(folder.resolve("inside"))) throw new AssertionError("failed directory move left reservation or lost source");
      if(move(folder,restored)!=0 || !Files.readString(restored.resolve("inside")).equals("keep")) throw new AssertionError("directory move failed");
      if(move(restored,folder)!=0 || !Files.readString(folder.resolve("inside")).equals("keep")) throw new AssertionError("directory restore failed");
      if(move(folder,restored)!=0) throw new AssertionError("second directory move failed");
      Files.delete(restored.resolve("inside"));Files.delete(restored);
      Path broken=dir.resolve("broken"), movedLink=dir.resolve("moved-link");
      Files.createSymbolicLink(broken,Path.of("missing"));
      if(move(dest,broken)==0 || !Files.isSymbolicLink(broken) || !Files.exists(dest)) throw new AssertionError("overwrote dangling symlink");
      if(move(broken,movedLink)!=0 || !Files.readSymbolicLink(movedLink).equals(Path.of("missing"))) throw new AssertionError("symlink move failed");
      Files.delete(movedLink);
      Path missingTarget=dir.resolve("missing-target");
      if(move(dir.resolve("absent"),missingTarget)==0 || Files.exists(missingTarget)) throw new AssertionError("missing source left reservation");
      System.out.println("PASS: directories and restore, empty-directory and symlink conflicts, failed-move cleanup, symlink payload");
    } finally {
      try(var files=Files.list(dir)){ for(Path p:files.toList()) Files.delete(p); }
      Files.delete(dir);
    }
  }
}
