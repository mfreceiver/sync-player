package cn.vectory.syncplayer.data.repository

import cn.vectory.syncplayer.data.local.dao.SyncRelationDao
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.entity.SyncRelationEntity
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

