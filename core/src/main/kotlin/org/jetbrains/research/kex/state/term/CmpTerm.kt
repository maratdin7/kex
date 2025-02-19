package org.jetbrains.research.kex.state.term

import kotlinx.serialization.ContextualSerialization
import kotlinx.serialization.Serializable
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.state.InheritorOf
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kex.util.defaultHashCode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode

@InheritorOf("Term")
@Serializable
class CmpTerm(
        override val type: KexType,
        @ContextualSerialization val opcode: CmpOpcode,
        val lhv: Term,
        val rhv: Term) : Term() {
    override val name = "$lhv $opcode $rhv"
    override val subterms by lazy { listOf(lhv, rhv) }

    override fun <T: Transformer<T>> accept(t: Transformer<T>): Term {
        val tlhv = t.transform(lhv)
        val trhv = t.transform(rhv)
        return when {
            tlhv == lhv && trhv == rhv -> this
            else -> term { tf.getCmp(opcode, tlhv, trhv) }
         }
    }

    override fun hashCode() = defaultHashCode(super.hashCode(), opcode)
    override fun equals(other: Any?): Boolean {
        if (other?.javaClass != this.javaClass) return false
        other as CmpTerm
        return super.equals(other) && this.opcode == other.opcode
    }
}