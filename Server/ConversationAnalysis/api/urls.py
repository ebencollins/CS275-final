from django.urls import path, include
from rest_framework.routers import SimpleRouter

from api import views

router = SimpleRouter()
router.register(r'conversations', views.ConversationViewSet)
router.register(r'devices', views.DeviceViewSet)

urlpatterns = [
    path('test', views.ConversationViewSet.as_view(actions={'get': 'list'})),
    path('', include(router.urls)),
]
