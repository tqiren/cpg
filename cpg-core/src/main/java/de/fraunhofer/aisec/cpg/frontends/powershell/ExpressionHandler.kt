/*
 * Copyright (c) 2022, Fraunhofer AISEC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */
package de.fraunhofer.aisec.cpg.frontends.powershell

import de.fraunhofer.aisec.cpg.ExperimentalPowerShell
import de.fraunhofer.aisec.cpg.frontends.Handler
import de.fraunhofer.aisec.cpg.graph.NodeBuilder
import de.fraunhofer.aisec.cpg.graph.declarations.FunctionDeclaration
import de.fraunhofer.aisec.cpg.graph.statements.expressions.*
import de.fraunhofer.aisec.cpg.graph.types.TypeParser
import de.fraunhofer.aisec.cpg.graph.types.UnknownType

@ExperimentalPowerShell
public class ExpressionHandler(lang: PowerShellLanguageFrontend) :
    Handler<Expression, PowerShellNode, PowerShellLanguageFrontend>(::Expression, lang) {
    init {
        map.put(PowerShellNode::class.java, ::handleNode)
    }

    private fun handleNode(node: PowerShellNode): Expression {
        println("EXPRESSION:  ${node.type}")
        when (node.type) {
            // Wrapper AST classes
            "CommandExpressionAst" -> return handleWrapperExpression(node)
            "ParenExpressionAst" -> return handleWrapperExpression(node)
            "StatementBlockAst" -> return handleWrapperExpression(node)
            "ScriptBlockExpressionAst" -> return handleWrapperExpression(node)
            "PipelineAst" -> return handlePipelineExpression(node)
            "ScriptBlockAst" -> return handleScriptBlock(node)

            // AST Parents passed in to handle children
            "CommandAst" -> return handleCommand(node)
            "CommandParameterAst" -> return handleDeclaredReferenceExpression(node)

            // PS allows certain statements to be expressions
            "SwitchStatementAst" -> return handleSwitchExpr(node)

            // actual "expression"
            "VariableExpressionAst" -> return handleDeclaredReferenceExpression(node)
            "AssignmentStatementAst" -> return handleBinaryExpression(node) // same as binary
            "BinaryExpressionAst" -> return handleBinaryExpression(node)
            "UnaryExpressionAst" -> return handleUnaryBinaryExpression(node)
            "ConstantExpressionAst" -> return handleLiteralExpression(node)
            "StringConstantExpressionAst" -> return handleLiteralExpression(node)
            "FunctionDefinitionAst" -> return handleDeclaration(node)
            "ArrayExpressionAst" -> return handleArrayExpression(node)
            "ConvertExpressionAst" -> return handleConvertExpression(node)

            // TODO
            "InvokeMemberExpressionAst" -> return handleMemberExpr(node)
        }
        log.warn("EXPRESSION: Not handled situations: ${node.type}")
        return Expression()
    }

    // Current solution is to treat it as compoundStatementExpression
    //   where it is both a statement and expression so that it has more flexibility to do anything
    //   Also assumes that this is a wrapper to "NamedBlockAst"
    private fun handleScriptBlock(node: PowerShellNode): Expression {
        val compoundExprStmt = NodeBuilder.newCompoundStatementExpression(node.code!!)
        if (node.children!!.size == 1) {
            compoundExprStmt.statement = this.lang.statementHandler.handle(node.children!![0])
        } else if (node.children!!.size > 1) {
            // TODO add tagging of each statement perhaps with begin, process, end NamedBlockAst
            // blocks
            val compoundStmt = NodeBuilder.newCompoundStatement(node.code)
            this.lang.scopeManager.enterScope(compoundStmt)
            for (child in node.children!!) {
                compoundStmt.addStatement(this.lang.statementHandler.handle(child))
            }
            this.lang.scopeManager.leaveScope(compoundStmt)
            compoundExprStmt.statement = compoundStmt
        } else {
            // Should not happen.
            log.error("ScriptBlock has no children")
        }
        return compoundExprStmt
    }

    // First of a pipeline can be expression.
    // The rest cannot.
    private fun handlePipelineExpression(node: PowerShellNode): Expression {
        return if (node.children!!.size == 1) {
            this.handle(node.children!![0])
        } else {
            // TODO Extremely buggy need to fix this.
            val compoundExprStmt = NodeBuilder.newCompoundStatementExpression(node.code!!)
            compoundExprStmt.statement = this.lang.statementHandler.handleGenericBlock(node)
            return compoundExprStmt
        }
    }

    private fun handleDeclaration(node: PowerShellNode): Expression {
        val exp = NodeBuilder.newExpression(node.code)
        exp.addDeclaration(this.lang.declarationHandler.handle(node))
        return exp
    }
    /**
     * handles an ast representing an expression when the expression is used as the first command of
     * a pipeline. appears to just be some wrapper ast? not sure what other purpose this serves but
     * for now it just serves as another wrapper to other expressionNodes
     */
    private fun handleWrapperExpression(node: PowerShellNode): Expression {
        // 1. CommandExpressionAst - The ast representing an expression when the expression is used
        // as the first command of a pipeline.
        // 2. PipelineAst
        // Have only seen it with 1 child but this assumption may not hold.
        if (node.children!!.count() != 1) {
            log.error("FIX ME: CommandExpressionAst has more than 1 child")
        }
        return this.handle(node.children!![0])
    }

    private fun handleLiteralExpression(node: PowerShellNode): Expression {
        val typeStr =
            when (node.type) {
                "ConstantExpressionAst" -> "int"
                "StringConstantExpressionAst" -> "str"
                // should not fall into this? if it does, add them
                else -> "unknown"
            }
        if (typeStr == "unknown")
            log.warn("Unidentified type found for literal: type is actually ${node.type}")

        val type = TypeParser.createFrom(typeStr, false)
        val value = node.code

        val lit = NodeBuilder.newLiteral(value, type, node.code)
        lit.name = node.code ?: node.name ?: ""
        lit.location = this.lang.getLocationFromRawNode(node)
        return lit
    }

    private fun handleBinaryExpression(node: PowerShellNode): Expression {
        if (node.operator == null) println("binaryExpression has no operator?")
        val operatorCode = node.operator!!
        val binaryOperator = NodeBuilder.newBinaryOperator(operatorCode, node.code)
        val lhs = handle(node.children!![0])
        val rhs = handle(node.children!![1])

        binaryOperator.lhs = lhs
        binaryOperator.rhs = rhs
        binaryOperator.type = lhs.type ?: rhs.type
        return binaryOperator
    }

    // Only have ++ and --
    private fun handleUnaryBinaryExpression(node: PowerShellNode): Expression {
        val token = node.unaryType!!
        var operator = ""
        var postFix = false
        var preFix = false
        when (token) {
            "PostfixPlusPlus" -> {
                operator = "++"
                postFix = true
                preFix = false
            }
            "PostfixMinusMinus" -> {
                operator = "--"
                postFix = true
                preFix = false
            }
            "PrefixPlusPlus" -> {
                operator = "++"
                postFix = false
                preFix = true
            }
            "PrefixMinusMinus" -> {
                operator = "--"
                postFix = false
                preFix = true
            }
        }
        if (operator == "") println("FIX ME: unary operator has no operator")
        val unaryOperator = NodeBuilder.newUnaryOperator(operator, postFix, preFix, node.code)
        unaryOperator.input = handle(node.children!![0])
        return unaryOperator
    }

    private fun handleDeclaredReferenceExpression(node: PowerShellNode): Expression {
        val name = this.lang.getIdentifierName(node)
        val type = node.codeType?.let { TypeParser.createFrom(it, false) }
        val ref =
            NodeBuilder.newDeclaredReferenceExpression(
                name,
                type ?: UnknownType.getUnknownType(),
                this.lang.getCodeFromRawNode(node)
            )
        ref.location = this.lang.getLocationFromRawNode(node)
        return ref
    }

    private fun handleArrayExpression(node: PowerShellNode): Expression {
        val expr = NodeBuilder.newInitializerListExpression(node.code)
        expr.type = node.codeType?.let { TypeParser.createFrom(it, false) }

        val retList: MutableList<PowerShellNode> = emptyList<PowerShellNode>().toMutableList()
        val items = this.lang.getAllLastChildren(node, retList)
        val list: MutableList<Expression> = emptyList<Expression>().toMutableList()
        for (item in items) {
            list.add(this.handle(item))
        }
        expr.setInitializers(list)
        return expr
    }

    /**
     * Handles all AST type of "CommandAst" Examples include function calls, cmdlet (instances of
     * .NET classes)
     */
    // Type here is CommandAst
    private fun handleCommand(node: PowerShellNode): Expression {
        // First child is always the function call itself
        val functionCallAst = node.children!![0]
        if (functionCallAst.type != "StringConstantExpressionAst") {
            log.error("First child in handleCommand not the function called")
        }
        val functionCall =
            NodeBuilder.newCallExpression(
                functionCallAst.code,
                functionCallAst.code,
                node.code,
                false
            )
        // Finding the function definition to get the function params' index number
        val functionDefList =
            this.lang.scopeManager.resolveFunctionStopScopeTraversalOnDefinition(functionCall)
        // Does not mean much as it could simply be the case of multiple class with same function
        // declaration
        // and since the ^ function searches ALL parent scope for that definition, many instances
        // can be returned.
        // Nonetheless, the FIRST occurrence should be the correct definition. Warning logged just
        // in case.
        if (functionDefList.size > 1)
            log.warn(
                "function definition list has more than 1 definition found, may not be the correct function def"
            )

        val children = node.children!!.subList(1, (node.children!!.size))
        if (children.isNotEmpty()) {
            processCommandArgs(functionCall, functionDefList, children)
        }

        return functionCall
    }

    private fun processCommandArgs(
        functionCall: CallExpression,
        functionDef: List<FunctionDeclaration>,
        paramsList: List<PowerShellNode>
    ) {
        val paramsMap: MutableMap<String, Int> = emptyMap<String, Int>().toMutableMap()
        val isDeclared: Boolean = functionDef.isNotEmpty()

        if (isDeclared) {
            val functionCalled = functionDef[0]
            for (parameter in functionCalled.parameters) {
                paramsMap[parameter.name] = parameter.argumentIndex
            }
        }
        // can have mixed so need to handle them together

        var doneList = emptyList<Int>().toMutableList()
        for ((index, param) in paramsList.withIndex()) {
            if (index in doneList) continue
            if (param.type == "CommandParameterAst") {
                val paramName = param.code!!.replace("-", "$")
                val paramValue = this.handle(paramsList[index + 1])
                doneList.add(index + 1)
                paramValue.argumentIndex = paramsMap[paramName] ?: index
                functionCall.addArgument(paramValue)
            } else if (param.type == "ScriptBlockExpressionAst") {
                log.warn("HI")
                functionCall.addArgument(this.lang.expressionHandler.handle(param))
            } else {
                // The middle AST structures do not contain information that are essential
                // Plus they can intertwine between ArrayLiteralAst and ParenExpressionAst,
                // making it extremely complex to handle.
                // We are only interested in the last nodes (those without children)
                val retList: MutableList<PowerShellNode> =
                    emptyList<PowerShellNode>().toMutableList()
                val params = this.lang.getAllLastChildren(param, retList)
                for ((id, p) in params.withIndex()) {
                    val paramValue = this.handle(p)
                    paramValue.argumentIndex = id
                    functionCall.addArgument(paramValue)
                }
            }
        }
    }

    private fun handleSwitchExpr(node: PowerShellNode): Expression {
        val expr = NodeBuilder.newCompoundStatementExpression(node.code!!)
        expr.statement = this.lang.statementHandler.handle(node)
        return expr
    }

    private fun handleConvertExpression(node: PowerShellNode): Expression {
        if (node.children!!.size != 2) log.error("convertExpression has more than 2 children")
        val varNode = node.children!![1]

        val cast = NodeBuilder.newCastExpression(node.code)
        cast.expression = this.handle(varNode)
        cast.castType = TypeParser.createFrom(node.codeType!!, false)
        return cast
    }

    private fun handleMemberExpr(node: PowerShellNode): Expression {
        return Expression()
    }
}
