package com.halvorai.ingest;

import java.util.concurrent.Callable;

/**
 * Attributes payments to an agent, its owner and the instruction it was given. The context is
 * held per thread; nested contexts keep the outer value of any field they leave null.
 *
 * <pre>{@code
 * try (var scope = AgentContext.of(
 *         Agent.of("agt_travel_12").name("Travel booking agent"),
 *         Owner.of("own_u_4410", "person").country("US"),
 *         Instruction.of("task_8890", "Book a return flight to Austin under $500")).open()) {
 *   halvor.emit(event);
 * }
 * }</pre>
 *
 * <p>A thread pool does not inherit the context: capture it with {@link #wrap(Runnable)} or
 * {@link #wrapCallable(Callable)}, or pass it to {@link HalvorClient#emit(Object, AgentContext)}.
 */
public final class AgentContext {
  private static final ThreadLocal<AgentContext> CURRENT = new ThreadLocal<>();

  private final Agent agent;
  private final Owner owner;
  private final Instruction instruction;

  private AgentContext(Agent agent, Owner owner, Instruction instruction) {
    this.agent = agent;
    this.owner = owner;
    this.instruction = instruction;
  }

  /** A context; any argument may be null. */
  public static AgentContext of(Agent agent, Owner owner, Instruction instruction) {
    return new AgentContext(agent, owner, instruction);
  }

  /** The context of the current thread, or null. */
  public static AgentContext current() {
    return CURRENT.get();
  }

  public Agent agent() { return agent; }
  public Owner owner() { return owner; }
  public Instruction instruction() { return instruction; }

  /** This context with the outer one's values where this one has none. */
  AgentContext over(AgentContext outer) {
    if (outer == null) return this;
    return new AgentContext(
        agent != null ? agent : outer.agent,
        owner != null ? owner : outer.owner,
        instruction != null ? instruction : outer.instruction);
  }

  /** Makes this context current on this thread until the returned scope is closed. */
  public Scope open() {
    AgentContext previous = CURRENT.get();
    CURRENT.set(over(previous));
    return new Scope(previous);
  }

  /** Runs a task with this context. */
  public void run(Runnable task) {
    try (Scope ignored = open()) {
      task.run();
    }
  }

  /** Calls a task with this context. */
  public <T> T call(Callable<T> task) throws Exception {
    try (Scope ignored = open()) {
      return task.call();
    }
  }

  /** Wraps a task so it runs with the current thread's context, wherever it runs. */
  public static Runnable wrap(Runnable task) {
    AgentContext captured = current();
    return captured == null ? task : () -> captured.run(task);
  }

  /** Wraps a task so it runs with the current thread's context, wherever it runs. */
  public static <T> Callable<T> wrapCallable(Callable<T> task) {
    AgentContext captured = current();
    return captured == null ? task : () -> captured.call(task);
  }

  /** Restores the previous context when closed. */
  public static final class Scope implements AutoCloseable {
    private final AgentContext previous;
    private boolean closed;

    private Scope(AgentContext previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}
