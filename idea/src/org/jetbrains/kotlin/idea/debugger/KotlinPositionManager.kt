/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.debugger

import com.intellij.debugger.MultiRequestPositionManager
import com.intellij.debugger.NoDataException
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.PositionManagerEx
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.libraries.LibraryUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState
import com.intellij.xdebugger.frame.XStackFrame
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.request.ClassPrepareRequest
import org.jetbrains.kotlin.codegen.binding.CodegenBinding
import org.jetbrains.kotlin.codegen.inline.InlineCodegenUtil
import org.jetbrains.kotlin.codegen.state.JetTypeMapper
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.fileClasses.NoResolveFileClassesProvider
import org.jetbrains.kotlin.fileClasses.getFileClassInternalName
import org.jetbrains.kotlin.fileClasses.internalNameWithoutInnerClasses
import org.jetbrains.kotlin.idea.codeInsight.CodeInsightUtils
import org.jetbrains.kotlin.idea.debugger.breakpoints.getLambdasAtLineIfAny
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinCodeFragmentFactory
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinDebuggerCaches
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinDebuggerCaches.Companion.getOrComputeClassNames
import org.jetbrains.kotlin.idea.decompiler.classFile.KtClsFile
import org.jetbrains.kotlin.idea.refactoring.getLineStartOffset
import org.jetbrains.kotlin.idea.search.usagesSearch.isImportUsage
import org.jetbrains.kotlin.idea.util.DebuggerUtils
import org.jetbrains.kotlin.idea.util.ProjectRootsUtil
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import java.util.*
import com.intellij.debugger.engine.DebuggerUtils as JDebuggerUtils

class KotlinPositionManager(private val myDebugProcess: DebugProcess) : MultiRequestPositionManager, PositionManagerEx() {

    override fun evaluateCondition(context: EvaluationContext, frame: StackFrameProxyImpl, location: Location, expression: String): ThreeState? {
        return ThreeState.UNSURE
    }

    override fun createStackFrame(frame: StackFrameProxyImpl, debugProcess: DebugProcessImpl, location: Location): XStackFrame? {
        if (location.declaringType().containsKotlinStrata()) {
            return KotlinStackFrame(frame)
        }
        return null
    }

    override fun getSourcePosition(location: Location?): SourcePosition? {
        if (location == null) {
            throw NoDataException.INSTANCE
        }

        val psiFile = getPsiFileByLocation(location)
        if (psiFile == null) {
            val isKotlinStrataAvailable = location.declaringType().containsKotlinStrata()
            if (isKotlinStrataAvailable) {
                try {
                    val javaSourceFileName = location.sourceName("Java")
                    val javaClassName = JvmClassName.byInternalName(defaultInternalName(location))
                    val project = myDebugProcess.project

                    val defaultPsiFile = DebuggerUtils.findSourceFileForClass(project, myDebugProcess.searchScope, javaClassName, javaSourceFileName)
                    if (defaultPsiFile != null) {
                        return SourcePosition.createFromLine(defaultPsiFile, 0)
                    }
                }
                catch(e: AbsentInformationException) {
                    // ignored
                }
            }

            throw NoDataException.INSTANCE
        }

        val lineNumber = try {
            location.lineNumber() - 1
        }
        catch (e: InternalError) {
            -1
        }


        if (lineNumber >= 0) {
            val lambdaOrFunIfInside = getLambdaOrFunIfInside(location, psiFile as KtFile, lineNumber)
            if (lambdaOrFunIfInside != null) {
                return SourcePosition.createFromElement(lambdaOrFunIfInside.bodyExpression!!)
            }
            val property = getParameterIfInConstructor(location, psiFile, lineNumber)
            if (property != null) {
                return SourcePosition.createFromElement(property)
            }
            return SourcePosition.createFromLine(psiFile, lineNumber)
        }

        throw NoDataException.INSTANCE
    }

    private fun getParameterIfInConstructor(location: Location, file: KtFile, lineNumber: Int): KtParameter? {
        val lineStartOffset = file.getLineStartOffset(lineNumber) ?: return null
        val elementAt = file.findElementAt(lineStartOffset)
        val contextElement = KotlinCodeFragmentFactory.getContextElement(elementAt)
        val methodName = location.method().name()
        if (contextElement is KtClass && JvmAbi.isGetterName(methodName)) {
            val parameterForGetter = contextElement.getPrimaryConstructor()?.valueParameters?.firstOrNull() {
                it.hasValOrVar() && it.name != null && JvmAbi.getterName(it.name!!) == methodName
            } ?: return null
            return parameterForGetter
        }
        return null
    }

    private fun getLambdaOrFunIfInside(location: Location, file: KtFile, lineNumber: Int): KtFunction? {
        val currentLocationFqName = location.declaringType().name()
        if (currentLocationFqName == null) return null

        val start = CodeInsightUtils.getStartLineOffset(file, lineNumber)
        val end = CodeInsightUtils.getEndLineOffset(file, lineNumber)
        if (start == null || end == null) return null

        val literalsOrFunctions = getLambdasAtLineIfAny(file, lineNumber)
        if (literalsOrFunctions.isEmpty()) return null;

        val elementAt = file.findElementAt(start) ?: return null
        val typeMapper = KotlinDebuggerCaches.getOrCreateTypeMapper(elementAt)

        val currentLocationClassName = JvmClassName.byFqNameWithoutInnerClasses(FqName(currentLocationFqName)).internalName
        for (literal in literalsOrFunctions) {
            if (InlineUtil.isInlinedArgument(literal, typeMapper.bindingContext, true)) {
                if (isInsideInlineArgument(literal, location, myDebugProcess as DebugProcessImpl)) {
                    return literal
                }
                continue
            }

            val internalClassNames = classNamesForPosition(literal.firstChild)
            if (internalClassNames.any { it == currentLocationClassName }) {
                return literal
            }
        }

        return null
    }

    private fun getPsiFileByLocation(location: Location): PsiFile? {
        val sourceName: String
        try {
            sourceName = location.sourceName()
        }
        catch (e: AbsentInformationException) {
            return null
        }
        catch (e: InternalError) {
            return null
        }


        val referenceInternalName: String
        try {
            if (location.declaringType().containsKotlinStrata()) {
                //replace is required for windows
                referenceInternalName = location.sourcePath().replace('\\', '/')
            }
            else {
                referenceInternalName = defaultInternalName(location)
            }
        }
        catch (e: AbsentInformationException) {
            referenceInternalName = defaultInternalName(location)
        }

        val className = JvmClassName.byInternalName(referenceInternalName)

        val project = myDebugProcess.project

        return DebuggerUtils.findSourceFileForClass(project, myDebugProcess.searchScope, className, sourceName)
    }

    private fun defaultInternalName(location: Location): String {
        //no stratum or source path => use default one
        val referenceFqName = location.declaringType().name()
        // JDI names are of form "package.Class$InnerClass"
        return referenceFqName.replace('.', '/')
    }

    override fun getAllClasses(sourcePosition: SourcePosition): List<ReferenceType> {
        val psiFile = sourcePosition.file
        if (psiFile is KtFile) {
            val result = ArrayList<ReferenceType>()

            if (!ProjectRootsUtil.isInProjectOrLibSource(psiFile)) return result

            val names = classNamesForPosition(sourcePosition)
            for (name in names) {
                result.addAll(myDebugProcess.virtualMachineProxy.classesByName(name))
            }
            return result
        }

        if (psiFile is ClsFileImpl) {
            val decompiledPsiFile = runReadAction { psiFile.decompiledPsiFile }
            if (decompiledPsiFile is KtClsFile && sourcePosition.line == -1) {
                val className =
                        JvmFileClassUtil.getFileClassInfoNoResolve(decompiledPsiFile).fileClassFqName.internalNameWithoutInnerClasses
                return myDebugProcess.virtualMachineProxy.classesByName(className)
            }
        }

        throw NoDataException.INSTANCE
    }

    fun originalClassNameForPosition(sourcePosition: SourcePosition): String? {
        return classNamesForPosition(sourcePosition).firstOrNull()
    }

    private fun classNamesForPosition(sourcePosition: SourcePosition): List<String> {
        val element = runReadAction { sourcePosition.elementAt } ?: return emptyList()
        val names = classNamesForPosition(element)

        val lambdas = findLambdas(sourcePosition)
        if (lambdas.isEmpty()) {
            return names
        }

        return names + lambdas
    }

    private fun classNamesForPosition(element: PsiElement): List<String> {
        return runReadAction {
            if (DumbService.getInstance(element.project).isDumb) {
                emptyList()
            }
            else {
                val baseElement = getElementToCalculateClassName(element) ?: return@runReadAction emptyList()
                getOrComputeClassNames(baseElement) {
                    element ->
                    val file = element.containingFile as KtFile
                    val isInLibrary = LibraryUtil.findLibraryEntry(file.virtualFile, file.project) != null
                    val typeMapper = KotlinDebuggerCaches.getOrCreateTypeMapper(element)

                    getInternalClassNameForElement(element, typeMapper, file, isInLibrary)
                }
            }
        }
    }

    private fun findLambdas(sourcePosition: SourcePosition): Collection<String> {
        val lambdas = runReadAction { getLambdasAtLineIfAny(sourcePosition) }
        return lambdas.flatMap { classNamesForPosition(it) }
    }

    override fun locationsOfLine(type: ReferenceType, position: SourcePosition): List<Location> {
        if (position.file !is KtFile) {
            throw NoDataException.INSTANCE
        }
        try {
            val line = position.line + 1
            val locations = if (myDebugProcess.virtualMachineProxy.versionHigher("1.4"))
                type.locationsOfLine("Kotlin", null, line)
            else
                type.locationsOfLine(line)
            if (locations == null || locations.isEmpty()) throw NoDataException.INSTANCE
            return locations
        }
        catch (e: AbsentInformationException) {
            throw NoDataException.INSTANCE
        }
    }

    @Deprecated("Since Idea 14.0.3 use createPrepareRequests fun")
    override fun createPrepareRequest(classPrepareRequestor: ClassPrepareRequestor, sourcePosition: SourcePosition): ClassPrepareRequest? {
        return createPrepareRequests(classPrepareRequestor, sourcePosition).firstOrNull()
    }

    override fun createPrepareRequests(requestor: ClassPrepareRequestor, position: SourcePosition): List<ClassPrepareRequest> {
        if (position.file !is KtFile) {
            throw NoDataException.INSTANCE
        }

        return classNamesForPosition(position).mapNotNull {
            className -> myDebugProcess.requestsManager.createClassPrepareRequest(requestor, className.replace('/', '.'))
        }
    }

    // The order is used in ExtraSteppingFilter: original className should be the first
    private fun getInternalClassNameForElement(
            element: KtElement,
            typeMapper: JetTypeMapper,
            file: KtFile,
            isInLibrary: Boolean
    ): List<String> {
        when (element) {
            is KtClassOrObject -> return getJvmInternalNameForImpl(typeMapper, element).toList()
            is KtFunction -> {
                val descriptor = InlineUtil.getInlineArgumentDescriptor(element, typeMapper.bindingContext)
                if (descriptor != null) {
                    val classNamesForParent = classNamesForPosition(element.parent)
                    if (descriptor.isCrossinline) {
                        return classNamesForParent + findCrossInlineArguments(element, descriptor, typeMapper.bindingContext)
                    }
                    return classNamesForParent
                }
            }
        }

        val crossInlineParameterUsages = element.containsCrossInlineParameterUsages(typeMapper.bindingContext)
        if (crossInlineParameterUsages.isNotEmpty()) {
            return classNamesForCrossInlineParameters(crossInlineParameterUsages, typeMapper.bindingContext).toList()
        }

        when {
            element is KtFunctionLiteral -> {
                val asmType = CodegenBinding.asmTypeForAnonymousClass(typeMapper.bindingContext, element)
                return asmType.internalName.toList()
            }
            element is KtAnonymousInitializer -> {
                val parent = getElementToCalculateClassName(element.parent)
                // Class-object initializer
                if (parent is KtObjectDeclaration && parent.isCompanion()) {
                    return classNamesForPosition(parent.parent)
                }
                return classNamesForPosition(element.parent)
            }
            element is KtPropertyAccessor && (!element.property.isTopLevel || !isInLibrary)-> {
                val classOrObject = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java)
                if (classOrObject != null) {
                    return getJvmInternalNameForImpl(typeMapper, classOrObject).toList()
                }
            }
            element is KtProperty && (!element.isTopLevel || !isInLibrary) -> {
                val descriptor = typeMapper.bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, element)
                if (descriptor !is PropertyDescriptor) {
                    return classNamesForPosition(element.parent)
                }

                return getJvmInternalNameForPropertyOwner(typeMapper, descriptor).toList()
            }
            element is KtNamedFunction -> {
                val parent = getElementToCalculateClassName(element.parent)
                val parentInternalName = if (parent is KtClassOrObject) {
                    getJvmInternalNameForImpl(typeMapper, parent)
                }
                else if (parent != null) {
                    val asmType = CodegenBinding.asmTypeForAnonymousClass(typeMapper.bindingContext, element)
                    asmType.internalName
                }
                else {
                    NoResolveFileClassesProvider.getFileClassInternalName(file)
                }

                val inlinedCalls = findInlinedCalls(element, typeMapper.bindingContext)
                return parentInternalName.toList() + inlinedCalls
            }
        }

        return NoResolveFileClassesProvider.getFileClassInternalName(file).toList()
    }

    private val TYPES_TO_CALCULATE_CLASSNAME: Array<Class<out KtElement>> =
            arrayOf(KtClassOrObject::class.java,
                    KtFunctionLiteral::class.java,
                    KtNamedFunction::class.java,
                    KtProperty::class.java,
                    KtPropertyAccessor::class.java,
                    KtAnonymousInitializer::class.java)

    private fun getElementToCalculateClassName(notPositionedElement: PsiElement?): KtElement? {
        if (notPositionedElement?.javaClass as Class<*> in TYPES_TO_CALCULATE_CLASSNAME) return notPositionedElement as KtElement

        return PsiTreeUtil.getParentOfType(notPositionedElement, *TYPES_TO_CALCULATE_CLASSNAME)
    }

    fun getJvmInternalNameForPropertyOwner(typeMapper: JetTypeMapper, descriptor: PropertyDescriptor): String {
        return typeMapper.mapOwner(
                if (JvmAbi.isPropertyWithBackingFieldInOuterClass(descriptor)) descriptor.containingDeclaration else descriptor
        ).internalName
    }

    private fun getJvmInternalNameForImpl(typeMapper: JetTypeMapper, ktClass: KtClassOrObject): String? {
        val classDescriptor = typeMapper.bindingContext.get<PsiElement, ClassDescriptor>(BindingContext.CLASS, ktClass) ?: return null

        if (ktClass is KtClass && ktClass.isInterface()) {
            return typeMapper.mapDefaultImpls(classDescriptor).internalName
        }

        return typeMapper.mapClass(classDescriptor).internalName
    }

    private fun findInlinedCalls(function: KtNamedFunction, context: BindingContext): Set<String> {
        return runReadAction {
            val result = hashSetOf<String>()

            if (InlineUtil.isInline(context.get(BindingContext.DECLARATION_TO_DESCRIPTOR, function))) {
                ReferencesSearch.search(function).forEach {
                    if (!it.isImportUsage()) {
                        val usage = it.element
                        if (usage is KtElement) {
                            //TODO recursive search
                            val names = classNamesForPosition(usage)
                            result.addAll(names)
                        }
                    }
                    true
                }
            }
            result
        }
    }

    private fun findCrossInlineArguments(argument: KtFunction, parameterDescriptor: ValueParameterDescriptor, context: BindingContext): Set<String> {
        return runReadAction {
            val source = parameterDescriptor.source.getPsi() as? KtParameter
            val functionName = source?.ownerFunction?.name
            if (functionName != null) {
                return@runReadAction setOf(getCrossInlineArgumentClassName(argument, functionName, context))
            }
            return@runReadAction emptySet()
        }
    }

    private fun getCrossInlineArgumentClassName(argument: KtFunction, inlineFunctionName: String, context: BindingContext): String {
        val anonymousClassNameForArgument = CodegenBinding.asmTypeForAnonymousClass(context, argument).internalName
        val newName = anonymousClassNameForArgument.substringIndex() + InlineCodegenUtil.INLINE_TRANSFORMATION_SUFFIX + "$" + inlineFunctionName
        return "$newName$*"
    }

    private fun KtElement.containsCrossInlineParameterUsages(context: BindingContext): Collection<ValueParameterDescriptor> {
        fun KtElement.hasParameterCall(parameter: KtParameter): Boolean {
            return ReferencesSearch.search(parameter).any {
                this.textRange.contains(it.element.textRange)
            }
        }

        val inlineFunction = this.parents.firstIsInstanceOrNull<KtNamedFunction>() ?: return emptySet()

        val inlineFunctionDescriptor = context[BindingContext.FUNCTION, inlineFunction]
        if (inlineFunctionDescriptor == null || !InlineUtil.isInline(inlineFunctionDescriptor)) return emptySet()

        return inlineFunctionDescriptor.valueParameters
                .filter { it.isCrossinline }
                .mapNotNull {
                    val psiParameter = it.source.getPsi() as? KtParameter
                    if (psiParameter != null && this@containsCrossInlineParameterUsages.hasParameterCall(psiParameter))
                        it
                    else
                        null
                }
    }

    private fun classNamesForCrossInlineParameters(usedParameters: Collection<ValueParameterDescriptor>, context: BindingContext): Set<String> {
        // We could calculate className only for one of parameters, because we add '*' to match all crossInlined parameter calls
        val parameter = usedParameters.first()
        val result = hashSetOf<String>()
        val inlineFunction = parameter.containingDeclaration.source.getPsi() as? KtNamedFunction ?: return emptySet()

        ReferencesSearch.search(inlineFunction).forEach {
            if (!it.isImportUsage()) {
                val call = (it.element as? KtExpression)?.let { KtPsiUtil.getParentCallIfPresent(it) }
                if (call != null) {
                    val resolvedCall = call.getResolvedCall(context)
                    val argument = resolvedCall?.valueArguments?.entries?.firstOrNull { it.key.original == parameter }?.value
                    if (argument != null) {
                        val argumentExpression = getArgumentExpression(argument.arguments.first())
                        if (argumentExpression is KtFunction) {
                            result.add(getCrossInlineArgumentClassName(argumentExpression, inlineFunction.name!!, context))
                        }
                    }
                }
            }
            true
        }

        return result
    }

    private fun getArgumentExpression(it: ValueArgument) = (it.getArgumentExpression() as? KtLambdaExpression)?.functionLiteral ?: it.getArgumentExpression()

    private fun String.substringIndex(): String {
        if (lastIndexOf("$") < 0) return this

        val suffix = substringAfterLast("$")
        if (suffix.all { it.isDigit() }) {
            return substringBeforeLast("$") + "$"
        }
        return this
    }

    private fun ReferenceType.containsKotlinStrata() = availableStrata().contains("Kotlin")

    private fun String?.toList() = if (this == null) emptyList() else listOf(this)
}
