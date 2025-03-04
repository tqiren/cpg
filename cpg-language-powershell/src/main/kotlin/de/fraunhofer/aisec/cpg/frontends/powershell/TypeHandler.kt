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
import de.fraunhofer.aisec.cpg.graph.types.Type
import de.fraunhofer.aisec.cpg.graph.types.TypeParser
import de.fraunhofer.aisec.cpg.graph.types.UnknownType

@ExperimentalPowerShell
class TypeHandler(lang: PowerShellLanguageFrontend) :
    Handler<Type, PowerShellNode, PowerShellLanguageFrontend>(
        { UnknownType.getUnknownType() },
        lang,
    ) {
    init {
        map.put(PowerShellNode::class.java, ::handleNode)
    }

    fun handleNode(node: PowerShellNode): Type {
        when (node.type) {
            "TypeReference" -> return handleTypeReference(node)
        }
        return UnknownType.getUnknownType()
    }

    private fun handleTypeReference(node: PowerShellNode): Type {
        node.firstChild("Identifier")?.let {
            return TypeParser.createFrom(this.lang.getIdentifierName(node), false)
        }
        return UnknownType.getUnknownType()
    }
}
