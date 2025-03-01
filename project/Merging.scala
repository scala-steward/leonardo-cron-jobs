import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy): (String => MergeStrategy) = {
    case PathList("io", "sundr", _ @_*)                   => MergeStrategy.first
    case PathList("org", "bouncycastle", _ @_*)           => MergeStrategy.first
    case PathList("com", "google", "code", "gson", _ @_*) => MergeStrategy.first
    case "reference.conf"                                 => MergeStrategy.concat
    case x if x.endsWith("/module-info.class") =>
      MergeStrategy.discard // JDK 8 does not use the file module-info.class so it is safe to discard the file.
    case "module-info.class" =>
      MergeStrategy.discard
    case x => oldStrategy(x)
  }
}
