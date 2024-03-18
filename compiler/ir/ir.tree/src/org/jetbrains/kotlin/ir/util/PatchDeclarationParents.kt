/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor

fun <T : IrElement> T.patchDeclarationParents(initialParent: IrDeclarationParent? = null) = apply {
    accept(PatchDeclarationParentsVisitor, initialParent)
}

abstract class DeclarationParentsVisitor : IrElementVisitor<Unit, IrDeclarationParent?> {
    override fun visitElement(element: IrElement, data: IrDeclarationParent?) {
        element.acceptChildren(this, data)
    }

    override fun visitPackageFragment(declaration: IrPackageFragment, data: IrDeclarationParent?) {
        declaration.acceptChildren(this, declaration)
    }

    override fun visitDeclaration(declaration: IrDeclarationBase, data: IrDeclarationParent?) {
        if (data != null) {
            handleParent(declaration, data)
        }

        val downParent = declaration as? IrDeclarationParent ?: data
        declaration.acceptChildren(this, downParent)
    }

    protected abstract fun handleParent(declaration: IrDeclaration, parent: IrDeclarationParent)
}

private object PatchDeclarationParentsVisitor : DeclarationParentsVisitor() {
    override fun handleParent(declaration: IrDeclaration, parent: IrDeclarationParent) {
        declaration.parent = parent
    }
}
