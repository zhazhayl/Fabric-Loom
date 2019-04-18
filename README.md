# Fabric Loom - Sin² Edition
A fork of [Fabric's Gradle plugin](https://github.com/FabricMC/fabric-loom/tree/dev/0.2) to make it do things asie didn't want it to do.

Usage: `gradlew genSources eclipse/idea/vscode`
(Use `./gradle` on macOS and Linux)


## What's new?
* [FernFlower](https://github.com/FabricMC/intellij-fernflower) switched to [ForgeFlower](https://github.com/MinecraftForge/ForgeFlower) for `genSources`
* Support for Enigma mappings (and parameter names as a result)
* Support for gz compressed Tiny mappings
* Access Transformers
* Easier additional remapped jar tasks


## What do I need to change?
Whilst not a whole lot needs to change compared to a normal Loom setup, there is a tweak that has to be made in order to get said setup running. Both the Forge and Jitpack mavens are needed to grab ForgeFlower and a [Tiny Remapper fork](https://github.com/Chocohead/tiny-remapper) respectively in order for Sin² to work:
```groovy
maven {
	name = "Forge"
	url = "https://files.minecraftforge.net/maven/"
}
maven { 
	name = "Jitpack"
	url = "https://jitpack.io/"
}
```
If using a Gradle setup similar to the [Fabric Example Mod](https://github.com/FabricMC/fabric-example-mod), these will want to be added to the `pluginManagement` `repositories` block in `settings.gradle`.

If using a more stockish Gradle setup, these will want to be added to the `buildscript` `repositories` block in `build.gradle` instead.



## How do I use the new things?
Once you've added the two maven repositories to your Gradle setup, ForgeFlower decompiling will be used for `genSources`. For the other additional features however, more changes are needed:

### Running with Enigma mappings
Normal Tiny files don't ship with parameter or local variable names, whilst Enigma mappings do. Thus in order to get parameter mappings for methods, Enigma mappings have to be used instead. This causes additional excitment as the Enigma mappings don't come with [Intermediary mappings](https://github.com/FabricMC/intermediary). Fortunately this is all be handled in the background and the additional Intermediaries will be downloaded if needed for the version of Minecraft being used. Several more steps will be noticed in the build process as a result as the two mapping sets then need to be merged and rewritten to the expected Tiny format used later by Loom. This only needs to happen once every time the mappings are changed though, so it's not so bad.

If previously the mappings dependency looked like
```groovy
mappings "net.fabricmc:yarn:19w13a.2"
```
In order to use the compressed form, it would need to be changed to
```groovy
mappings "net.fabricmc:yarn:19w13a.2:enigma@zip"
```
Nothing else is required, when the project is next evaluated the change will be detected by the lack of a method parameters file and thus the mappings rebuilt. In theory at least, it's normally quite good at behaving.  
<br />
When running Minecraft, Fabric Loader expects the jar distribution of Yarn rather than Enigma. This causes some trouble as the Mixin remapping will fail and cause sad times to be had. If using `runClient` this should be automatically handled from the jar distribution being added as a `runtimeOnly` dependency. If running from an IDE however it might not see the dependency, in which case the jar distribution can be added as if a normal Java dependency:
```groovy
implementation "net.fabricmc:yarn:19w13a.2"
```

### Running with gz compressed Tiny mappings
Whilst not making that much of a difference in the grand scheme of things, using the compressed Tiny mappings over the normal jar distribution does save you an entire kilobyte of downloading. And like using Enigma mappings you'll also still need the jar distribution if you're using `runClient` due to Fabric Loader. It's the thought that counts really.

If previously the mappings dependency looked like
```groovy
mappings "net.fabricmc:yarn:19w13a.2"
```
In order to use the compressed form, it would need to be changed to
```groovy
mappings "net.fabricmc:yarn:19w13a.2:tiny@gz"
```
Fairly simple stuff, just like with Enigma. Only without the obvious benefits.


### Access Transformers
Sin² provides dev time access transformations for making Minecraft classes and methods public (and non-final). For an explaination of how to use this, as well as the runtime component for using the ATs in game, see [here](https://github.com/Chocohead/Fabric-ASM#sailing-the-shenanigans).


### Additional tasks
Sin² adds an additional task type for producing remapping jars from other source sets on top of what the default `jar` task makes. It avoids the gotcha that a [`RemapJar`](https://github.com/Chocohead/fabric-loom/blob/ATs/src/main/java/net/fabricmc/loom/task/RemapJar.java) type task has in that the jar has to be supplied from elsewhere already made. [`RemappingJar`](https://github.com/Chocohead/fabric-loom/blob/ATs/src/main/java/net/fabricmc/loom/task/RemappingJar.java) is an extension of the normal `Jar` task which both remaps the output, and can optionally include the access transformer for the project:
```groovy
task exampleJar(type: RemappingJar, dependsOn: exampleClasses) {
	from sourceSets.example.output
	includeAT = false
}
```
The example source set will now produce a seperate jar which doesn't include the (remapped) access transformer file. Like the normal `Jar` task as many files can be added to the compilation set as desired.


## What's broken?
Ideally not much, although there are a few things to be aware of whilst using Sin²:

#### Propagation warnings during Minecraft jar remapping with Enigma
Parameter names aren't consistently added to methods in Engima, so as a result most methods don't have any parameter names even when they are applied. To try work around this slightly the names are propagated about to extended methods in case they don't have them, but being parameters there's nothing that says they should match. As a result there are several mismatches where extensions of methods have different (or no) names for some (or all) parameters which are logged by Tiny Remapper. This is not a necessarily bad thing, just a result of the mapping situation with Yarn.

#### Decompile error for `Structure#process(IWorld, BlockPos, StructurePlacementData, List)`
ForgeFlower appears to have a bug in [`MergeHelper#drillNots`](https://github.com/MinecraftForge/ForgeFlower/blob/master/FernFlower-Patches/0009-LVT-Fixes-and-Support-for-Enhanced-For-loop-detectio.patch#L1341-L1360) which causes it to return null when a while loop null checks as a break condition before testing for `hasNext`. It's the only method as of 19w13a which has this issue, and decompiling `Structure` using Procyon, stock FernFlower, CFR or any other decompiler you might have/want should give you an interpretation of the source if you so need it.
