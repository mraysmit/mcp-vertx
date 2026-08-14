package dev.mars.mcp.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory and utility methods for building the tool map exposed by the MCP
 * server.
 *
 * <p>Two factory methods support different composition patterns:
 * <ul>
 *   <li>{@link #of(Tool...)} — builds a map from an arbitrary set of
 *       tools.</li>
 *   <li>{@link #withAdditional(Map, Tool...)} — merges extra tools into
 *       an existing map, allowing incremental extension without editing
 *       this class.</li>
 * </ul>
 *
 * @see Tool
 */
public final class ToolRegistry {
  private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

  private ToolRegistry() {}

  /**
   * Build a tool map from an arbitrary set of tools.
   *
   * @param tools the tools to include
   * @return an unmodifiable map of tool-name → tool instance
   * @throws IllegalStateException if two tools share the same name
   */
  public static Map<String, Tool> of(Tool... tools) {
    LOG.atDebug().log(() -> "Building MCP tool registry from " + tools.length + " provider(s)");
    Map<String, Tool> registry = List.of(tools).stream()
      .collect(Collectors.toUnmodifiableMap(Tool::name, t -> t));
    LOG.atInfo().log(() -> "MCP tool registry created: tools=" + registry.size());
    LOG.atDebug().log(() -> "Registered MCP tools: " + registry.keySet().stream().sorted().toList());
    return registry;
  }

  /**
   * Merge additional tools into an existing tool map.
   *
   * <p>If an extra tool has the same name as one in the base map, it
   * <em>replaces</em> the base entry, allowing overrides.
   *
   * @param base   the starting tool map
   * @param extras additional tools to add or override
   * @return an unmodifiable merged map
   */
  public static Map<String, Tool> withAdditional(Map<String, Tool> base, Tool... extras) {
    LOG.atDebug().log(() -> "Extending MCP tool registry: base=" + base.size()
        + " extras=" + extras.length);
    Map<String, Tool> merged = new HashMap<>(base);
    for (Tool t : extras) {
      if (merged.containsKey(t.name())) {
        LOG.atInfo().log(() -> "Replacing MCP tool registration: tool=" + t.name());
      }
      merged.put(t.name(), t);
    }
    Map<String, Tool> registry = Map.copyOf(merged);
    LOG.atInfo().log(() -> "MCP tool registry extended: tools=" + registry.size());
    LOG.atDebug().log(() -> "Registered MCP tools: " + registry.keySet().stream().sorted().toList());
    return registry;
  }
}
