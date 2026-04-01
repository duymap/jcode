# Improvement Plan - jcode v0.2.0

## Priority 1: Security Hardening (Critical)

### 1.1 Sanitize Command Execution in BashTool
**File**: `src/main/java/com/jcode/tools/BashTool.java`  
**Issue**: Line 46 executes commands directly without validation

```java
// Current (vulnerable):
ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);

// Recommended:
if (!isValidCommand(command)) {
    throw new SecurityException("Invalid or dangerous command detected");
}
ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
```

**Implementation**: Add whitelist of allowed commands or regex validation for safe patterns. Block dangerous patterns like `rm -rf`, `mkfs`, etc.

### 1.2 Sanitize Command Execution in AppRunner
**File**: `src/main/java/com/jcode/tui/AppRunner.java`  
**Issue**: Line 427 executes user commands directly

```java
// Add input validation before ProcessBuilder usage
if (command.matches(".*(;|\\||&&|`|\\$).*")) {
    throw new SecurityException("Pipe characters not allowed in readonly mode");
}
ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
```

### 1.3 Sanitize Command Execution in ContextBuilder
**File**: `src/main/java/com/jcode/ContextBuilder.java`  
**Issue**: Line 111 executes commands directly

Add same validation pattern as BashTool before executing any shell commands.

---

## Priority 2: Test Coverage (High)

### 2.1 Add Unit Tests for Tool Interface
**File**: `src/test/java/com/jcode/tools/ToolTest.java`  
Create base test class for all tools with common assertions.

### 2.2 Implement Specific Tool Tests
- **ReadFileToolTest**: Test offset, limit, and error handling
- **WriteFileToolTest**: Test file creation and overwriting
- **BashToolTest**: Test command execution and timeout handling
- **GrepToolTest**: Test regex matching patterns

### 2.3 Add Integration Tests for AgentSession
**File**: `src/test/java/com/jcode/AgentSessionTest.java`  
Test the LLM ↔ tool execution loop with mock responses.

---

## Priority 3: Static Analysis (Medium)

### 3.1 Add SpotBugs to pom.xml
Add dependency and configure in build section:
```xml
<plugin>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs-maven-plugin</artifactId>
    <version>4.8.6.2</version>
</plugin>
```

### 3.2 Add Checkstyle for Code Quality
Add dependency to enforce coding standards:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-checkstyle-plugin</artifactId>
    <version>3.6.0</version>
</plugin>
```

### 3.3 Configure CI/CD Pipeline
Add GitHub Actions workflow to run:
- `mvn spotbugs:check` on every PR
- `mvn checkstyle:check` on commit
- `mvn test` with coverage reporting

---

## Priority 4: Dependency Management (Low)

### 4.1 Add OWASP Dependency Check
Configure in pom.xml for vulnerability scanning:
```xml
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>9.0.10</version>
    <executions>
        <execution>
            <goals>
                <goal>check</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 4.2 Regular Dependency Updates
Set up Dependabot or Renovate for automatic dependency updates.

---

## Implementation Timeline

| Week | Tasks | Priority |
|------|-------|----------|
| 1 | Fix BashTool command injection vulnerability | Critical |
| 1 | Add input validation to AppRunner and ContextBuilder | Critical |
| 2 | Create base test infrastructure (JUnit 5) | High |
| 2-3 | Implement unit tests for all Tools | High |
| 3 | Configure SpotBugs and Checkstyle in pom.xml | Medium |
| 4 | Set up CI/CD pipeline with automated checks | Medium |
| 4 | Add OWASP Dependency Check to build process | Low |

---

## Success Metrics

- [ ] Zero high-severity security issues (SpotBugs)
- [ ] ≥70% test coverage on core modules
- [ ] Zero new vulnerabilities in dependencies
- [ ] CI/CD pipeline passes on every commit
- [ ] No command injection vulnerabilities in shell execution paths
