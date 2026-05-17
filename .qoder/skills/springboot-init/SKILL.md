---
name: springboot-init
description: 用户创建 springboot 项目，包含后端和前端，并内置好登陆的前后端，实现完整的登陆功能
---

## 1. 初始化完整的项目目录

### 1.1 创建 pom.xml 文件，作为父项目

- 指定完整项目名称，如果没有指定，像用户提问
- 指定包名，默认为`com.agnetix.admin`

### 1.2 在项目中引入一些属性配置和依赖

- 指定版本
    * 指定项目编码为 UTF-8
    * 指定 Java 版本为 21
    * 指定 Spring Boot 版本为 3.5.8

```xml

<properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <spring-boot.version>3.5.8</spring-boot.version>
    <mvn.compile.version>3.11.0</mvn.compile.version>
</properties>
```

- 不需要真实引入依赖，只需在 `dependencyManagement` 中指定 `spring-boot-dependencies` 即可

```xml

<dependencyManagement>
    <dependencies>
        <!-- Spring Boot Dependencies -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

- build 里面指定插件管理，如 `spring-boot-maven-plugin` 和 `maven-compiler-plugin`

```xml

<build>
    <pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring-boot.version}</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>${mvn.compile.version}</version>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                </configuration>
            </plugin>
        </plugins>
    </pluginManagement>
</build>
```

## 1. 创建后端项目

### 1.1 组件

* **spring boot 3**
* **java17**
* **mybatis**
* **mybatis-plus**
* **swagger3**
* **WebFlux**

## 2. 创建前端项目

### 2.1 组件

* **Ant Design Pro**
* **Umi4**
