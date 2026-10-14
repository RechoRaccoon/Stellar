// The Kotlin/Wasm loader fetches its binary by its literal file name
// ("./stellar.wasm"); webpack's content-hashed asset names would break
// that lookup, so assets keep their own names.
config.output.assetModuleFilename = "[name][ext]";
