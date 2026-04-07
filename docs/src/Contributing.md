# Contributing

Thank you for your interest in contributing to Stormify! Contributions are welcome and appreciated. Whether you’re fixing bugs, adding new features, or improving documentation, your input helps make this project better. This section provides guidelines for contributing to the project, including setting up your development environment, submitting issues, creating pull requests, and following the code of conduct.

## How to Contribute

### Setting Up the Development Environment

To contribute code, you'll need to set up your local development environment:

1. **Fork the Repository**: Start by forking the [Stormify repository](https://github.com/teras/stormify) to your GitHub account.

2. **Clone the Repository**: Clone your fork to your local machine:

   ```
   git clone https://github.com/your-username/stormify.git
   ```

3. **Set Up the Project**: Navigate into the project directory and set up the project:

   ```
   cd stormify
   ./gradlew clean build
   ```

   Ensure all tests pass before making changes.

4. **Create a Branch**: Create a new branch for your work:

   ```
   git checkout -b feature/your-feature-name
   ```

5. **Make Your Changes**: Implement your changes, following the project's coding standards.

6. **Run Tests**: Verify that your changes pass all tests:

   ```
   ./gradlew test
   ```

### Guidelines for Contributing Code

- **Code Style**: Follow the coding style used in the project. Ensure your code is well-documented and adheres to Java best practices.
- **Commit Messages**: Write clear and descriptive commit messages. Each commit should represent a logical unit of work.
- **Testing**: Include tests for any new features or bug fixes. Ensure that all tests pass before submitting your changes.

## Submitting Issues

If you encounter a bug or have a feature request, please submit an issue on [GitHub](https://github.com/teras/stormify/issues). This helps the community and maintainers track and address problems efficiently.

### How to Report Issues

- **Bug Reports**: Include detailed information such as the version of Stormify, steps to reproduce the issue, expected behavior, and any relevant logs or error messages.
- **Feature Requests**: Describe the feature you'd like to see, why it's needed, and any potential implementation ideas.

Example issue template:

```
**Description**
A clear and concise description of the bug or feature request.

**Steps to Reproduce**
1. Step one
2. Step two
3. Step three

**Expected Behavior**
Describe what you expected to happen.

**Environment**
- Stormify version:
- Database type and version:
- JVM version:
  ```

## Submitting Pull Requests

To submit a pull request:

1. **Push Your Changes**: Push your changes to your fork:

   ```
   git push origin feature/your-feature-name
   ```

2. **Create a Pull Request**: Go to the original [Stormify repository](https://github.com/teras/stormify) and click on "New Pull Request". Select your branch and provide a clear description of your changes.

3. **Address Feedback**: Be prepared to make changes based on feedback from the project maintainers. Code reviews are an essential part of the contribution process to ensure quality and maintainability.

### Pull Request Guidelines

- **Keep it Focused**: Each pull request should focus on a single issue or feature.
- **Include Tests**: Ensure that your pull request includes tests for any new functionality or bug fixes.
- **Documentation**: Update or add documentation as necessary.

## Code of Conduct

We are committed to creating a welcoming and inclusive environment for all contributors. Please adhere to the following guidelines when interacting in the Stormify community:

- **Be Respectful**: Treat others with respect and consideration. Disagreements are natural but must be handled professionally.
- **Be Constructive**: Provide constructive feedback and be open to feedback on your contributions.
- **Be Inclusive**: Ensure that your contributions are accessible and considerate of different perspectives.

For more details, please refer to the project's [Code of Conduct](https://github.com/teras/stormify/blob/main/CODE_OF_CONDUCT.md).

By following these guidelines, you help maintain a positive and productive environment for everyone involved in the project. Thank you for contributing to Stormify!
