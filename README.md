# Distributed Task Queue System (Mini Celery)

A comprehensive distributed task queue system built with Java Spring Boot, Apache Kafka, Redis, and React.js. This system provides asynchronous task processing, real-time monitoring, retry mechanisms, scalable worker execution, and secure user authentication.

## 🏗️ Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   React.js      │    │   Spring Boot   │    │     Kafka       │
│   Dashboard     │◄──►│   Backend       │◄──►│   Message       │
│   (Frontend)    │    │   (API/Worker)  │    │   Queue         │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │
                                ▼
                       ┌─────────────────┐
                       │     Redis       │
                       │   (Cache/State) │
                       └─────────────────┘
```

## 🚀 Features

- **User Authentication**: Secure registration, login, JWT-based API protection
- **Asynchronous Task Processing**: Submit tasks that run in the background
- **Priority Queues**: Support for LOW, NORMAL, HIGH, and URGENT priority levels
- **Retry Mechanism**: Automatic retry with exponential backoff for failed tasks
- **Real-time Monitoring**: WebSocket-based dashboard for live task updates
- **Scalable Workers**: Docker-based worker scaling
- **Task Types**: Built-in support for email, image processing, data export, and report generation
- **Persistence**: Redis-based task state management
- **RESTful API**: Complete REST API for task management
- **Statistics**: Comprehensive task execution statistics

## 🛠️ Technology Stack

- **Backend**: Java 11, Spring Boot 2.7, Spring Security, Spring Kafka, JWT
- **Message Queue**: Apache Kafka
- **Cache/Storage**: Redis, H2 (for users)
- **Frontend**: React.js with Material-UI, TypeScript
- **Containerization**: Docker & Docker Compose
- **Build Tool**: Maven

## 📋 Prerequisites

- Java 11 or higher
- Maven 3.6+
- Docker & Docker Compose
- Node.js 14+ (for React dashboard)

## 🚦 How to Run the Project

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/distributed-task-queue.git
cd distributed-task-queue
```

### 2. Start Infrastructure Services

```bash
# Start Kafka, Zookeeper, and Redis
docker-compose up -d kafka redis zookeeper
```

### 3. Start the Backend (Spring Boot)

```bash
# In the project root
git pull # (if needed)
mvn clean package
mvn spring-boot:run
```
- The backend will run on [http://localhost:8080](http://localhost:8080)

### 4. Start the Frontend (React)

```bash
cd task-queue-frontend
npm install
npm start
```
- The frontend will run on [http://localhost:3000](http://localhost:3000)

### 5. Access the Application

- **Frontend Dashboard**: http://localhost:3000
- **API**: http://localhost:8080
- **H2 Console**: http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:testdb`)

## 🔐 Authentication

- **Register**: Create a new user account via the frontend or POST `/auth/register`
- **Login**: Obtain a JWT token via the frontend or POST `/auth/login`
- **JWT**: All API requests (except `/auth/*`) require the `Authorization: Bearer <token>` header
- **Logout**: Use the frontend logout button

### Authentication Endpoints

```http
POST /auth/register
{
  "username": "youruser",
  "email": "your@email.com",
  "password": "yourpassword"
}

POST /auth/login
{
  "username": "youruser",
  "password": "yourpassword"
}

# Response:
{
  "token": "<JWT_TOKEN>",
  "username": "youruser",
  "role": "USER"
}
```

### Protecting API Requests
Add the JWT token to the `Authorization` header:

```
Authorization: Bearer <JWT_TOKEN>
```

## 🐳 Docker Deployment

### Complete Stack Deployment

```bash
# Build and start all services
docker-compose up --build

# Scale workers
docker-compose up --scale task-queue-app=3
```

### Individual Service Management

```bash
# Start only infrastructure
docker-compose up -d kafka redis zookeeper

# Start application
docker-compose up task-queue-app

# View logs
docker-compose logs -f task-queue-app
```

## 📊 API Endpoints

### Task Management

```http
# Submit a new task
POST /api/tasks
{
  "type": "EMAIL_SEND",
  "payload": {
    "recipient": "user@example.com",
    "subject": "Test Email"
  },
  "priority": "HIGH"
}

# Get task by ID
GET /api/tasks/{taskId}

# Get all tasks
GET /api/tasks

# Get tasks by status
GET /api/tasks/status/{status}

# Get task statistics
GET /api/tasks/statistics

# Get active workers
GET /api/workers
```

### Task Types

1. **EMAIL_SEND**: Email sending tasks
   ```json
   {
     "type": "EMAIL_SEND",
     "payload": {
       "recipient": "user@example.com",
       "subject": "Test Email"
     }
   }
   ```

2. **IMAGE_PROCESS**: Image processing tasks
   ```json
   {
     "type": "IMAGE_PROCESS",
     "payload": {
       "imageUrl": "https://example.com/image.jpg",
       "operation": "resize"
     }
   }
   ```

3. **DATA_EXPORT**: Data export tasks
   ```json
   {
     "type": "DATA_EXPORT",
     "payload": {
       "format": "CSV",
       "recordCount": 1000
     }
   }
   ```

4. **REPORT_GENERATE**: Report generation tasks
   ```json
   {
     "type": "REPORT_GENERATE",
     "payload": {
       "reportType": "monthly",
       "dateRange": "2024-01"
     }
   }
   ```

## 📈 Monitoring & Metrics

### Dashboard Features

- **Real-time Task Updates**: WebSocket connection for live updates
- **Task Statistics**: Success rates, execution times, task counts
- **Visual Charts**: Pie charts for status distribution, bar charts for task types
- **Worker Monitoring**: Active worker status and health
- **Task Details**: Detailed view of individual tasks

### Key Metrics

- Total tasks processed
- Success rate percentage
- Average execution time
- Active worker count
- Task status distribution
- Task type distribution

---

**Enjoy your secure, distributed task queue system!**
