package io.cloudstate.javasupport.impl.crdt

import java.lang.reflect.{Constructor, Executable, InvocationTargetException}
import java.util.{Optional, function}
import java.util.function.Consumer

import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.{Context, ServiceCall, ServiceCallFactory}
import io.cloudstate.javasupport.crdt.{CommandContext, CommandHandler, Crdt, CrdtContext, CrdtCreationContext, CrdtEntity, CrdtEntityFactory, CrdtEntityHandler, Flag, GCounter, GSet, LWWRegister, LWWRegisterMap, ORMap, ORSet, PNCounter, PNCounterMap, StreamCancelledContext, StreamedCommandContext, SubscriptionContext, Vote}
import io.cloudstate.javasupport.impl.ReflectionHelper.{CommandHandlerInvoker, InvocationContext, MainArgumentParameterHandler, MethodParameter, ParameterHandler}
import io.cloudstate.javasupport.impl.{AnySupport, ReflectionHelper, ResolvedEntityFactory, ResolvedServiceMethod, ResolvedType}

import scala.reflect.ClassTag


/**
  * Annotation based implementation of the [[io.cloudstate.javasupport.crdt.CrdtEntityFactory]].
  */
private[impl] class AnnotationBasedCrdtSupport(entityClass: Class[_], anySupport: AnySupport,
  override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
  factory: Option[CrdtCreationContext => AnyRef] = None) extends CrdtEntityFactory with ResolvedEntityFactory {
  // TODO JavaDoc
  def this(entityClass: Class[_], anySupport: AnySupport,
    serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))


  private val constructor: CrdtCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on CRDT entities: $entityClass")
    }
  }

  private val (commandHandlers, streamedCommandHandlers) = {
    val allMethods = ReflectionHelper.getAllDeclaredMethods(entityClass)

    ReflectionHelper.validateNoBadMethods(allMethods, classOf[CrdtEntity], Set(classOf[CommandHandler]))
    val handlers = allMethods
      .filter(_.getAnnotation(classOf[CommandHandler]) != null)
      .map { method =>
        val annotation = method.getAnnotation(classOf[CommandHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = resolvedMethods.getOrElse(name, {
          throw new RuntimeException(s"Command handler method ${method.getName} for command $name found, but the service has no command by that name.")
        })
        (ReflectionHelper.ensureAccessible(method), serviceMethod)
      }

    def getHandlers[C <: CrdtContext : ClassTag](streamed: Boolean) =
      handlers.filter(_._2.outputStreamed == streamed)
      .map {
        case (method, serviceMethod) => new CommandHandlerInvoker[C](method, serviceMethod, CrdtAnnotationHelper.crdtParameterHandlers)
      }
      .groupBy(_.serviceMethod.name)
      .map {
        case (commandName, Seq(invoker)) => commandName -> invoker
        case (commandName, many) => throw new RuntimeException(s"Multiple methods found for handling command of name $commandName: ${many.map(_.method.getName)}")
      }

    (getHandlers[CommandContext](false), getHandlers[StreamedCommandContext[AnyRef]](true))
  }
  
  // TODO JavaDoc
  override def create(context: CrdtCreationContext): CrdtEntityHandler = {
    val entity = constructor(context)
    new EntityHandler(entity)
  }

  private class EntityHandler(entity: AnyRef) extends CrdtEntityHandler {

    override def handleCommand(command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = unwrap {
      val maybeResult = commandHandlers.get(context.commandName()).map { handler =>
        handler.invoke(entity, command, context)
      }

      maybeResult.getOrElse {
        throw new RuntimeException(s"No command handler found for command [${context.commandName()}] on CRDT entity: $entityClass")
      }
    }

    override def handleStreamedCommand(command: JavaPbAny, context: StreamedCommandContext[JavaPbAny]): Optional[JavaPbAny] = unwrap {
      val maybeResult = streamedCommandHandlers.get(context.commandName()).map { handler =>
        val adaptedContext = new AdaptedStreamedCommandContext(context, handler.serviceMethod.outputType.asInstanceOf[ResolvedType[AnyRef]])
        handler.invoke(entity, command, adaptedContext)
      }

      maybeResult.getOrElse {
        throw new RuntimeException(s"No streamed command handler found for command [${context.commandName()}] on CRDT entity: $entityClass")
      }
    }

    private def unwrap[T](block: => T): T = try {
      block
    } catch {
      case ite: InvocationTargetException if ite.getCause != null =>
        throw ite.getCause
    }
  }

}

private object CrdtAnnotationHelper {
  val crdtParameterHandlers: PartialFunction[MethodParameter, ParameterHandler[CrdtContext]] = {
    case crdt if classOf[Crdt].isAssignableFrom(crdt.parameterType) =>
      new CrdtParameterHandler(crdt.parameterType.asInstanceOf[Class[_ <: Crdt]], crdt.method)
    case crdt if crdt.parameterType == classOf[Optional[_]] &&
      classOf[Crdt].isAssignableFrom(ReflectionHelper.getFirstParameter(crdt.genericParameterType)) =>
      new OptionalCrdtParameterHandler(ReflectionHelper.getFirstParameter(crdt.genericParameterType).asInstanceOf[Class[_ <: Crdt]], crdt.method)
  }

  private class CrdtParameterHandler(crdtClass: Class[_ <: Crdt], method: Executable) extends ParameterHandler[CrdtContext] {
    override def apply(ctx: InvocationContext[CrdtContext]): AnyRef = {
      val state = ctx.context.state(crdtClass)
      if (state.isPresent) {
        state.get()
      } else {
        throw new IllegalStateException(s"${method.getDeclaringClass.getName}.${method.getName} requires a CRDT " +
          s"of type ${crdtClass.getName}, but this entity has no CRDT created for it yet.")
      }
    }
  }

  private class OptionalCrdtParameterHandler(crdtClass: Class[_ <: Crdt], method: Executable) extends ParameterHandler[CrdtContext] {
    override def apply(ctx: InvocationContext[CrdtContext]): AnyRef = {
      ctx.context.state(crdtClass)
    }
  }

}

private final class AdaptedStreamedCommandContext(val delegate: StreamedCommandContext[JavaPbAny], resolvedType: ResolvedType[AnyRef]) extends StreamedCommandContext[AnyRef] {
  override def isStreamed: Boolean = delegate.isStreamed

  def onChange(subscriber: function.Function[SubscriptionContext, Optional[AnyRef]]): Unit = {
    delegate.onChange { ctx =>
      val result = subscriber(ctx)
      if (result.isPresent) {
        Optional.of(JavaPbAny.newBuilder()
            .setTypeUrl(resolvedType.typeUrl)
            .setValue(resolvedType.toByteString(result.get))
            .build()
        )
      } else {
        Optional.empty()
      }
    }
  }

  override def onCancel(effect: Consumer[StreamCancelledContext]): Unit = delegate.onCancel(effect)

  override def serviceCallFactory(): ServiceCallFactory = delegate.serviceCallFactory()
  override def entityId(): String = delegate.entityId()
  override def commandId(): Long = delegate.commandId()
  override def commandName(): String = delegate.commandName()

  override def state[T <: Crdt](crdtClass: Class[T]): Optional[T] = delegate.state(crdtClass)
  override def delete(): Unit = delegate.delete()

  override def forward(to: ServiceCall): Unit = delegate.forward(to)
  override def fail(errorMessage: String): RuntimeException = delegate.fail(errorMessage)
  override def effect(effect: ServiceCall, synchronous: Boolean): Unit = delegate.effect(effect, synchronous)

  override def newGCounter(): GCounter = delegate.newGCounter()
  override def newPNCounter(): PNCounter = delegate.newPNCounter()
  override def newGSet[T](): GSet[T] = delegate.newGSet()
  override def newORSet[T](): ORSet[T] = delegate.newORSet()
  override def newFlag(): Flag = delegate.newFlag()
  override def newLWWRegister[T](value: T): LWWRegister[T] = delegate.newLWWRegister(value)
  override def newORMap[K, V <: Crdt](): ORMap[K, V] = delegate.newORMap()
  override def newVote(): Vote = delegate.newVote()
}

private final class EntityConstructorInvoker(constructor: Constructor[_]) extends (CrdtCreationContext => AnyRef) {
  private val parameters = ReflectionHelper.getParameterHandlers[CrdtCreationContext](constructor)(CrdtAnnotationHelper.crdtParameterHandlers)
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in constructor")
    case _ =>
  }

  def apply(context: CrdtCreationContext): AnyRef = {
    val ctx = InvocationContext("", context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}


