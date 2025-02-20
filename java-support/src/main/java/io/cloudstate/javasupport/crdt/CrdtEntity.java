package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.impl.CloudStateAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A CRDT backed entity.
 * <p>
 * CRDT entities store their state in a subclass {@link Crdt}. These may be created using a {@link CrdtFactory}, which
 * can be injected into the constructor or as a parameter to any {@link CommandHandler} annotated method.
 * <p>
 * Only one CRDT may be created, it is important that before creating a CRDT, the entity should check whether the CRDT
 * has already been created, for example, it may have been created on another node and replicated to this node. To
 * check, either use the {@link CrdtContext#state(Class)} method, which can be injected into the constructor or any
 * {@link CommandHandler} method, or have an instance of the CRDT wrapped in {@link java.util.Optional} injected into
 * the constructor or command handler methods.
 */
@CloudStateAnnotation
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CrdtEntity {
}
