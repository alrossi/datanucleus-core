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
package org.datanucleus.enhancer.jdo;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.jdo.JDOException;
import javax.jdo.JDOFatalException;
import javax.jdo.JDOUserException;
import javax.jdo.spi.PersistenceCapable;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ImplementationCreator;
import org.datanucleus.enhancer.ClassEnhancer;
import org.datanucleus.enhancer.EnhancerClassLoader;
import org.datanucleus.enhancer.jdo.JDOImplementationGenerator;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.ClassMetaData;
import org.datanucleus.metadata.InterfaceMetaData;
import org.datanucleus.metadata.MetaDataManager;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;

/**
 * Creator of persistable objects using the ASM bytecode manipulation library.
 */
public class JDOImplementationCreator implements Serializable, ImplementationCreator
{
    protected static final Localiser LOCALISER = Localiser.getInstance(
        "org.datanucleus.Localisation", ClassEnhancer.class.getClassLoader());

    /** MetaData manager to use. */
    protected final MetaDataManager metaDataMgr;

    /** ClassLoader for newly defined classes **/
    protected final EnhancerClassLoader loader;

    /**
     * Constructor.
     * @param mmgr MetaData manager
     */
    public JDOImplementationCreator(MetaDataManager mmgr)
    {
        metaDataMgr = mmgr;
        loader = new EnhancerClassLoader();
    }

    /**
     * Accessor for the ClassLoader.
     * @return The ClassLoader
     */
    public ClassLoader getClassLoader()
    {
        return loader;
    }

    /**
     * Method to generate an instance of an interface, abstract class, or concrete PC class.
     * @param cls The class of the interface, abstract class, or concrete class defined in MetaData
     * @param clr ClassLoader resolver
     * @return The instance of this type
     */
    public Object newInstance(Class cls, ClassLoaderResolver clr)
    {
        try
        {
            if (PersistenceCapable.class.isAssignableFrom(cls))
            {
                if (Modifier.isAbstract(cls.getModifiers()))
                {
                    // Abstract class, so we need an implementation
                    ClassMetaData cmd = (ClassMetaData)metaDataMgr.getMetaDataForClass(cls, clr);
                    if (cmd == null)
                    {
                        throw new JDOFatalException("Could not find metadata for class " + cls.getName());
                    }

                    Object obj = newInstance(cmd, clr);
                    if (obj == null)
                    {
                        throw new JDOFatalException(LOCALISER.msg("ImplementationCreator.InstanceCreateFailed", cls.getName()));
                    }
                    if (!metaDataMgr.hasMetaDataForClass(obj.getClass().getName()))
                    {
                        // No metadata yet present for the implementation so register it
                        metaDataMgr.registerImplementationOfAbstractClass(cmd, obj.getClass(), clr);
                    }
                    return obj;
                }
                else
                {
                    // Concrete class that is PC so just create an instance using its no args constructor
                    return cls.newInstance();
                }
            }
            else
            {
                // Interface, so we need an implemenation
                InterfaceMetaData imd = metaDataMgr.getMetaDataForInterface(cls, clr);
                if (imd == null)
                {
                    throw new JDOFatalException("Could not find metadata for class/interface "+cls.getName());
                }

                Object obj = newInstance(imd, clr);
                if (obj == null)
                {
                    throw new JDOFatalException(LOCALISER.msg("ImplementationCreator.InstanceCreateFailed", cls.getName()));
                }
                if (!metaDataMgr.hasMetaDataForClass(obj.getClass().getName()))
                {
                    // No metadata yet present for the implementation so register it
                    metaDataMgr.registerPersistentInterface(imd, obj.getClass(), clr);
                }
                return obj;
            }
        }
        catch (ClassNotFoundException e)
        {
            throw new JDOUserException(e.toString(),e);
        }
        catch (InstantiationException e)
        {
            throw new JDOUserException(e.toString(),e);
        }
        catch (IllegalAccessException e)
        {
            throw new JDOUserException(e.toString(),e);
        }
    }

    /**
     * Constructs an implementation for an interface and instantiates it.
     * @param imd The MetaData for the interface
     * @param clr The ClassLoader
     * @return The instance implementing the interface
     * @throws ClassNotFoundException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */    
    protected PersistenceCapable newInstance(InterfaceMetaData imd, ClassLoaderResolver clr) 
    throws ClassNotFoundException, InstantiationException, IllegalAccessException
    {
        // Check that all methods of the interface are declared as persistent properties
        Class cls = clr.classForName(imd.getFullClassName());
        Method[] methods = cls.getDeclaredMethods();
        for (int i=0;i<methods.length;i++)
        {
            String methodName = methods[i].getName();
            if (!methodName.startsWith("jdo"))
            {
                String propertyName = methodName;
                if (methodName.startsWith("set"))
                {
                    propertyName = ClassUtils.getFieldNameForJavaBeanSetter(methodName);
                }
                else if (methodName.startsWith("get"))
                {
                    propertyName = ClassUtils.getFieldNameForJavaBeanGetter(methodName);
                }

                if (imd.getMetaDataForMember(propertyName) == null)
                {
                    throw new NucleusUserException(LOCALISER.msg("ImplementationCreator.InterfaceMethodUndefined",
                        imd.getFullClassName(), methodName));
                }
            }
        }

        // Generate the implementation
        String implClassName = imd.getName() + "Impl";
        String implFullClassName = imd.getPackageName() + '.' + implClassName;
        try
        {
            // Try to find the class
            this.loader.loadClass(implFullClassName);
        }
        catch (ClassNotFoundException e)
        {
            // Class not found so generate it
            JDOImplementationGenerator gen = getGenerator(imd, implClassName);
            gen.enhance(clr);
            this.loader.defineClass(implFullClassName, gen.getBytes(), clr);
        }

        // Create an instance of the class using default constructor
        Object instance = this.loader.loadClass(implFullClassName).newInstance();
        if (instance instanceof PersistenceCapable)
        {
            return (PersistenceCapable) instance;
        }
        else
        {
            // Generated instance is not PersistenceCapable for some reason so generate a suitable exception
            Class interfaces[] = instance.getClass().getInterfaces();
            StringBuilder implementedInterfacesMsg = new StringBuilder("[");
            String classLoaderPCMsg = "";
            for (int i=0; i<interfaces.length; i++)
            {
                implementedInterfacesMsg.append(interfaces[i].getName()); 
                if (i<interfaces.length-1)
                {
                    implementedInterfacesMsg.append(",");    
                }
                if (interfaces[i].getName().equals(PersistenceCapable.class.getName()))
                {
                    classLoaderPCMsg = LOCALISER.msg("ImplementationCreator.DifferentClassLoader", 
                        interfaces[i].getClassLoader(), PersistenceCapable.class.getClassLoader());
                }
            }
            implementedInterfacesMsg.append("]");

            throw new JDOException(LOCALISER.msg("ImplementationCreator.NotPCProblem", implFullClassName, 
                classLoaderPCMsg, implementedInterfacesMsg.toString()));
        }
    }

    /**
     * Constructs an implementation for an abstract class and instantiates it.
     * @param cmd The MetaData for the abstract class
     * @param clr The ClassLoader
     * @return The instance implementing the abstract class.
     * @throws ClassNotFoundException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */    
    protected PersistenceCapable newInstance(ClassMetaData cmd, ClassLoaderResolver clr) 
    throws ClassNotFoundException, InstantiationException, IllegalAccessException
    {
        // Check that all abstract methods of the class are declared as persistent properties
        Class cls = clr.classForName(cmd.getFullClassName());
        Method[] methods = cls.getDeclaredMethods();
        for (int i=0;i<methods.length;i++)
        {
            String methodName = methods[i].getName();
            if (Modifier.isAbstract(methods[i].getModifiers()) && !methodName.startsWith("jdo"))
            {
                String propertyName = methodName;
                if (methodName.startsWith("set"))
                {
                    propertyName = ClassUtils.getFieldNameForJavaBeanSetter(methodName);
                }
                else if (methodName.startsWith("get"))
                {
                    propertyName = ClassUtils.getFieldNameForJavaBeanGetter(methodName);
                }

                if (cmd.getMetaDataForMember(propertyName) == null)
                {
                    throw new NucleusUserException(LOCALISER.msg("ImplementationCreator.AbstractClassMethodUndefined",
                        cmd.getFullClassName(), methodName));
                }
            }
        }

        // Generate the implementation
        String implClassName = cmd.getName() + "Impl";
        String implFullClassName = cmd.getPackageName() + '.' + implClassName;
        try
        {
            // Try to find the class
            this.loader.loadClass(implFullClassName);
        }
        catch (ClassNotFoundException e)
        {
            // Class not found so generate it
            JDOImplementationGenerator gen = getGenerator(cmd, implClassName);
            gen.enhance(clr);
            this.loader.defineClass(implFullClassName, gen.getBytes(), clr);
        }

        // Create an instance of the class using default constructor
        Object instance = this.loader.loadClass(implFullClassName).newInstance();
        if (instance instanceof PersistenceCapable)
        {
            return (PersistenceCapable) instance;
        }
        else
        {
            // Generated instance is not PersistenceCapable for some reason so generate a suitable exception
            // TODO Correct this message for abstract classes
            Class interfaces[] = instance.getClass().getInterfaces();
            StringBuilder implementedInterfacesMsg = new StringBuilder("[");
            String classLoaderPCMsg = "";
            for (int i=0; i<interfaces.length; i++)
            {
                implementedInterfacesMsg.append(interfaces[i].getName()); 
                if (i<interfaces.length-1)
                {
                    implementedInterfacesMsg.append(",");    
                }
                if (interfaces[i].getName().equals(PersistenceCapable.class.getName()))
                {
                    classLoaderPCMsg = LOCALISER.msg("ImplementationCreator.DifferentClassLoader", 
                        interfaces[i].getClassLoader(), PersistenceCapable.class.getClassLoader());
                }
            }
            implementedInterfacesMsg.append("]");

            throw new JDOException(LOCALISER.msg("ImplementationCreator.NotPCProblem", implFullClassName, 
                classLoaderPCMsg, implementedInterfacesMsg.toString()));
        }
    }

    /**
     * Method to return the generator for the implementation.
     * @param acmd MetaData for the interface or abstract class
     * @param implClassName Name of the implementation class to create
     * @return The implementation generator
     */
    protected JDOImplementationGenerator getGenerator(AbstractClassMetaData acmd, String implClassName)
    {
        if (acmd instanceof InterfaceMetaData)
        {
            // Implementation of an interface
            return new JDOImplementationGenerator((InterfaceMetaData)acmd, implClassName, metaDataMgr);
        }
        else if (acmd instanceof ClassMetaData)
        {
            // Implementation of an abstract class
            return new JDOImplementationGenerator((ClassMetaData)acmd, implClassName, metaDataMgr);
        }
        return null;
    }
}