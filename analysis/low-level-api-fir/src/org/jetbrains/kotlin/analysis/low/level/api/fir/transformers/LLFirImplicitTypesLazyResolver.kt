/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.transformers

import org.jetbrains.kotlin.analysis.low.level.api.fir.api.targets.LLFirResolveTarget
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.throwUnexpectedFirElementError
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.builder.LLFirLockProvider
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirDeclarationModificationService
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.checkReturnTypeRefIsResolved
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.forEachDependentDeclaration
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.isScriptDependentDeclaration
import org.jetbrains.kotlin.fir.FirElementWithResolveState
import org.jetbrains.kotlin.fir.FirFileAnnotationsContainer
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.isCopyCreatedInScope
import org.jetbrains.kotlin.fir.resolve.ResolutionMode
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirImplicitAwareBodyResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirResolveContextCollector
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.ImplicitBodyResolveComputationSession
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.FirImplicitTypeRef
import org.jetbrains.kotlin.fir.util.setMultimapOf
import org.jetbrains.kotlin.fir.utils.exceptions.withFirSymbolEntry
import org.jetbrains.kotlin.fir.visitors.transformSingle
import org.jetbrains.kotlin.utils.exceptions.errorWithAttachment

internal object LLFirImplicitTypesLazyResolver : LLFirLazyResolver(FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE) {
    override fun resolve(
        target: LLFirResolveTarget,
        lockProvider: LLFirLockProvider,
        scopeSession: ScopeSession,
        towerDataContextCollector: FirResolveContextCollector?,
    ) {
        val resolver = LLFirImplicitBodyTargetResolver(target, lockProvider, scopeSession, towerDataContextCollector)
        resolver.resolveDesignation()
    }

    override fun phaseSpecificCheckIsResolved(target: FirElementWithResolveState) {
        if (target !is FirCallableDeclaration) return
        checkReturnTypeRefIsResolved(target)
    }
}

internal class LLImplicitBodyResolveComputationSession : ImplicitBodyResolveComputationSession() {
    /**
     * The symbol on which foreign annotations will be postponed
     *
     * @see withAnchorForForeignAnnotations
     * @see postponeForeignAnnotationResolution
     */
    private var anchorForForeignAnnotations: FirCallableSymbol<*>? = null

    inline fun <T> withAnchorForForeignAnnotations(symbol: FirCallableSymbol<*>, action: () -> T): T {
        val previousSymbol = anchorForForeignAnnotations
        return try {
            anchorForForeignAnnotations = symbol
            action()
        } finally {
            anchorForForeignAnnotations = previousSymbol
        }
    }

    override fun <D : FirCallableDeclaration> executeTransformation(symbol: FirCallableSymbol<*>, transformation: () -> D): D {
        // Do not store local declarations as we can postpone only non-local callables
        return if (symbol.cannotResolveAnnotationsOnDemand()) {
            transformation()
        } else {
            withAnchorForForeignAnnotations(symbol, transformation)
        }
    }

    private val postponedSymbols = setMultimapOf<FirCallableSymbol<*>, FirBasedSymbol<*>>()

    /**
     * Postpone the resolution request to [symbol] until [annotation arguments][FirResolvePhase.ANNOTATION_ARGUMENTS] phase
     * of the declaration which is used this foreign annotation.
     *
     * @see postponedSymbols
     */
    fun postponeForeignAnnotationResolution(symbol: FirBasedSymbol<*>) {
        // We should unwrap local symbols to avoid recursion
        // We cannot resolve them on demand, so we shouldn't postpone them
        val symbolToPostpone = symbol.symbolToPostponeIfCanBeResolvedOnDemand() ?: return
        val currentSymbol = anchorForForeignAnnotations ?: errorWithAttachment("Unexpected state: the current symbol have to be here") {
            withFirSymbolEntry("symbol to postpone", symbolToPostpone)
        }

        // There is no sense to postpone itself as it will lead to recursion
        if (currentSymbol == symbolToPostpone) return

        postponedSymbols.put(currentSymbol, symbolToPostpone)
    }

    /**
     * @return all symbols postponed with [postponeForeignAnnotationResolution] for the [target] element
     *
     * @see postponeForeignAnnotationResolution
     */
    fun postponedSymbols(target: FirCallableDeclaration): Collection<FirBasedSymbol<*>> {
        return postponedSymbols[target.symbol]
    }
}

internal class LLFirImplicitBodyTargetResolver(
    target: LLFirResolveTarget,
    lockProvider: LLFirLockProvider,
    scopeSession: ScopeSession,
    firResolveContextCollector: FirResolveContextCollector?,
    llImplicitBodyResolveComputationSessionParameter: LLImplicitBodyResolveComputationSession? = null,
) : LLFirAbstractBodyTargetResolver(
    target,
    lockProvider,
    scopeSession,
    FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE,
    llImplicitBodyResolveComputationSession = llImplicitBodyResolveComputationSessionParameter ?: LLImplicitBodyResolveComputationSession(),
    isJumpingPhase = true,
) {
    override val transformer = object : FirImplicitAwareBodyResolveTransformer(
        resolveTargetSession,
        implicitBodyResolveComputationSession = llImplicitBodyResolveComputationSession,
        phase = resolverPhase,
        implicitTypeOnly = true,
        scopeSession = scopeSession,
        firResolveContextCollector = firResolveContextCollector,
        returnTypeCalculator = createReturnTypeCalculator(firResolveContextCollector = firResolveContextCollector),
    ) {
        override val preserveCFGForClasses: Boolean get() = false
        override val buildCfgForScripts: Boolean get() = false
        override val buildCfgForFiles: Boolean get() = false
        override fun transformForeignAnnotationCall(symbol: FirBasedSymbol<*>, annotationCall: FirAnnotationCall): FirAnnotationCall {
            llImplicitBodyResolveComputationSession.postponeForeignAnnotationResolution(symbol)
            return annotationCall
        }
    }

    override fun doLazyResolveUnderLock(target: FirElementWithResolveState) {
        when {
            target is FirCallableDeclaration && target.isCopyCreatedInScope -> {
                transformer.returnTypeCalculator.callableCopyTypeCalculator.computeReturnType(target)
            }

            target is FirFunction -> {
                if (target.returnTypeRef is FirImplicitTypeRef) {
                    resolve(target, BodyStateKeepers.FUNCTION)
                }
            }

            target is FirProperty -> {
                if (target.returnTypeRef is FirImplicitTypeRef || target.backingField?.returnTypeRef is FirImplicitTypeRef) {
                    resolve(target, BodyStateKeepers.PROPERTY)
                }
            }

            target is FirField -> {
                if (target.returnTypeRef is FirImplicitTypeRef) {
                    resolve(target, BodyStateKeepers.FIELD)
                }
            }

            target is FirScript -> {
                if (target.declarations.any { it.isScriptDependentDeclaration }) {
                    resolve(target, BodyStateKeepers.SCRIPT)
                }
            }

            target is FirRegularClass ||
                    target is FirTypeAlias ||
                    target is FirFile ||
                    target is FirCodeFragment ||
                    target is FirAnonymousInitializer ||
                    target is FirDanglingModifierList ||
                    target is FirFileAnnotationsContainer ||
                    target is FirEnumEntry ||
                    target is FirErrorProperty
            -> {
                // No implicit bodies here
            }
            else -> throwUnexpectedFirElementError(target)
        }

        if (target is FirDeclaration) {
            target.forEachDeclarationWhichCanHavePostponedSymbols(::publishPostponedSymbols)
        }
    }

    private fun publishPostponedSymbols(target: FirCallableDeclaration) {
        val postponedSymbols = llImplicitBodyResolveComputationSession.postponedSymbols(target)
        if (postponedSymbols.isNotEmpty()) {
            target.postponedSymbolsForAnnotationResolution = postponedSymbols
        }
    }

    override fun rawResolve(target: FirElementWithResolveState) {
        when {
            target is FirScript -> {
                transformer.declarationsTransformer.withScript(target) {
                    target.forEachDependentDeclaration {
                        it.transformSingle(transformer, ResolutionMode.ContextIndependent)
                    }

                    target
                }
            }

            else -> super.rawResolve(target)
        }

        LLFirDeclarationModificationService.bodyResolved(target, resolverPhase)
    }
}
