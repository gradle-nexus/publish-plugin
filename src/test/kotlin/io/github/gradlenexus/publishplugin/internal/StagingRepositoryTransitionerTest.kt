/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gradlenexus.publishplugin.internal

import com.nhaarman.mockitokotlin2.anyOrNull
import io.github.gradlenexus.publishplugin.RepositoryTransitionException
import io.github.gradlenexus.publishplugin.StagingRepository
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.invocation.InvocationOnMock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
internal class StagingRepositoryTransitionerTest {

    companion object {
        private const val TEST_STAGING_REPO_ID = "orgexample-42"
    }

    @Mock
    private lateinit var nexusClient: NexusClient
    @Mock
    private lateinit var retrier: ActionRetrier<StagingRepository>

    private lateinit var transitioner: StagingRepositoryTransitioner

    @BeforeEach
    internal fun setUp() {
        transitioner = StagingRepositoryTransitioner(nexusClient, retrier)
    }

    @Test
    internal fun `request repository close and get its state after execution by retrier`() {
        given(nexusClient.getStagingRepositoryStateById(TEST_STAGING_REPO_ID))
                .willReturn(StagingRepository(TEST_STAGING_REPO_ID, StagingRepository.State.CLOSED, false))
        given(retrier.execute(anyOrNull())).willAnswer(executeFunctionPassedAsFirstArgument())

        transitioner.effectivelyClose(TEST_STAGING_REPO_ID)

        val inOrder = inOrder(nexusClient, retrier)
        inOrder.verify(nexusClient).closeStagingRepository(TEST_STAGING_REPO_ID)
        inOrder.verify(nexusClient).getStagingRepositoryStateById(TEST_STAGING_REPO_ID)
    }

    @Test
    internal fun `request release repository and get its state after execution by retrier`() {
        given(nexusClient.getStagingRepositoryStateById(TEST_STAGING_REPO_ID))
                .willReturn(StagingRepository(TEST_STAGING_REPO_ID, StagingRepository.State.NOT_FOUND, false))
        given(retrier.execute(anyOrNull())).willAnswer(executeFunctionPassedAsFirstArgument())

        transitioner.effectivelyRelease(TEST_STAGING_REPO_ID)

        val inOrder = inOrder(nexusClient, retrier)
        inOrder.verify(nexusClient).releaseStagingRepository(TEST_STAGING_REPO_ID)
        inOrder.verify(nexusClient).getStagingRepositoryStateById(TEST_STAGING_REPO_ID)
    }

    @Test
    internal fun `throw meaningful exception on repository still in transition on close`() {
        given(nexusClient.getStagingRepositoryStateById(TEST_STAGING_REPO_ID))
                .willReturn(StagingRepository(TEST_STAGING_REPO_ID, StagingRepository.State.CLOSED, true))
        given(retrier.execute(anyOrNull())).willAnswer(executeFunctionPassedAsFirstArgument())

        assertThatExceptionOfType(RepositoryTransitionException::class.java)
                .isThrownBy { transitioner.effectivelyClose(TEST_STAGING_REPO_ID) }
                .withMessageContainingAll(TEST_STAGING_REPO_ID, "transitioning=true")
    }

    @Test
    internal fun `throw meaningful exception on repository still in wrong state on close`() {
        given(nexusClient.getStagingRepositoryStateById(TEST_STAGING_REPO_ID))
                .willReturn(StagingRepository(TEST_STAGING_REPO_ID, StagingRepository.State.OPEN, false))
        given(retrier.execute(anyOrNull())).willAnswer(executeFunctionPassedAsFirstArgument())

        assertThatExceptionOfType(RepositoryTransitionException::class.java)
                .isThrownBy { transitioner.effectivelyClose(TEST_STAGING_REPO_ID) }
                .withMessageContainingAll(TEST_STAGING_REPO_ID, StagingRepository.State.OPEN.toString(), StagingRepository.State.CLOSED.toString())
    }

    private fun executeFunctionPassedAsFirstArgument(): (InvocationOnMock) -> StagingRepository {
        return { invocation: InvocationOnMock ->
            val passedFunction: () -> StagingRepository = invocation.getArgument(0)
            passedFunction.invoke()
        }
    }
}
