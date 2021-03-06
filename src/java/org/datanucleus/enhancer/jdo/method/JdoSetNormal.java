/**********************************************************************
Copyright (c) 2007 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.enhancer.jdo.method;

import org.datanucleus.asm.Label;
import org.datanucleus.asm.Opcodes;
import org.datanucleus.asm.Type;
import org.datanucleus.enhancer.ClassEnhancer;
import org.datanucleus.enhancer.ClassMethod;
import org.datanucleus.enhancer.EnhanceUtils;
import org.datanucleus.metadata.AbstractMemberMetaData;

/**
 * Method to generate the method "setZZZ" using ASM for NORMAL fields.
 * <pre>
 * static void setZZZ(MyClass objPC, YYY zzz)
 * {
 *     objPC.ZZZ = zzz;
 * }
 * </pre>
 */
public class JdoSetNormal extends ClassMethod
{
    /** Field that this setZZZ is for. */
    protected AbstractMemberMetaData fmd;

    /**
     * Constructor.
     * @param enhancer ClassEnhancer
     * @param fmd MetaData for the field we are generating for
     */
    public JdoSetNormal(ClassEnhancer enhancer, AbstractMemberMetaData fmd)
    {
        super(enhancer, enhancer.getNamer().getSetMethodPrefixMethodName() + fmd.getName(),
            (fmd.isPublic() ? Opcodes.ACC_PUBLIC : 0) | (fmd.isProtected() ? Opcodes.ACC_PROTECTED : 0) | 
            (fmd.isPrivate() ? Opcodes.ACC_PRIVATE : 0) | Opcodes.ACC_STATIC, null, null, null);

        // Set the arg types/names
        argTypes = new Class[] {getClassEnhancer().getClassBeingEnhanced(), fmd.getType()};
        argNames = new String[] {"objPC", "val"};

        this.fmd = fmd;
    }

    /**
     * Method to add the contents of the class method.
     */
    public void execute()
    {
        visitor.visitCode();

        String fieldTypeDesc = Type.getDescriptor(fmd.getType());

        Label startLabel = new Label();
        visitor.visitLabel(startLabel);

        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        EnhanceUtils.addLoadForType(visitor, fmd.getType(), 1);
        visitor.visitFieldInsn(Opcodes.PUTFIELD, getClassEnhancer().getASMClassName(), fmd.getName(), fieldTypeDesc);
        visitor.visitInsn(Opcodes.RETURN);

        Label endLabel = new Label();
        visitor.visitLabel(endLabel);
        visitor.visitLocalVariable(argNames[0], getClassEnhancer().getClassDescriptor(), null, startLabel, endLabel, 0);
        visitor.visitLocalVariable(argNames[1], fieldTypeDesc, null, startLabel, endLabel, 1);
        visitor.visitMaxs(2, 2);

        visitor.visitEnd();
    }
}