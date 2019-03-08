package net.fabricmc.loom.providers.mappings;

public interface IMappingAcceptor {
	void acceptClass(String srcName, String dstName);
	void acceptMethod(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc);
	void acceptMethodArg(String srcClsName, String srcMethodName, String srcMethodDesc, int argIndex, int lvIndex, String dstArgName);
	void acceptMethodVar(String srcClsName, String srcMethodName, String srcMethodDesc, int varIndex, int lvIndex, String dstVarName);
	void acceptField(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc);
}